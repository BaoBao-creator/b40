package b40.b40;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiCheatManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type WL_TYPE = new TypeToken<List<WhitelistEntry>>() {}.getType();
    private static final byte[] STATIC_SALT = "b40-v1-ac".getBytes(StandardCharsets.UTF_8);
    private static final int CHAIN_ROUNDS = 5;

    private final Map<UUID, CheckSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, List<ModFingerprint>> pendingSelections = new ConcurrentHashMap<>();
    private final List<WhitelistEntry> whitelist = new ArrayList<>();
    private Path whitelistPath;
    private net.minecraft.server.MinecraftServer currentServer;

    public static final Identifier CHALLENGE_S2C_ID = Identifier.fromNamespaceAndPath(B40.MOD_ID, "challenge_s2c");
    public static final Identifier CHALLENGE_C2S_ID = Identifier.fromNamespaceAndPath(B40.MOD_ID, "challenge_c2s");
    public static final Identifier MODLIST_REQUEST_S2C_ID = Identifier.fromNamespaceAndPath(B40.MOD_ID, "modlist_request_s2c");
    public static final Identifier MODLIST_C2S_ID = Identifier.fromNamespaceAndPath(B40.MOD_ID, "modlist_c2s");

    public void init() {
        PayloadTypeRegistry.playS2C().register(ChallengeS2CPayload.TYPE, ChallengeS2CPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ChallengeC2SPayload.TYPE, ChallengeC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ModListRequestPayload.TYPE, ModListRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ModListPayload.TYPE, ModListPayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            whitelistPath = FabricLoader.getInstance().getConfigDir().resolve("b40-whitelist.json");
            loadWhitelist();
        });
        registerEvents();
        registerNetworking();
        registerCommands();
    }

    private void registerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> beginCheck(handler.getPlayer()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                CheckSession s = sessions.get(p.getUUID());
                if (s != null && s.frozenPos != null) {
                    if (p.position().distanceToSqr(s.frozenPos.getX() + 0.5, s.frozenPos.getY(), s.frozenPos.getZ() + 0.5) > 0.01) {
                        p.teleportTo(s.frozenPos.getX() + 0.5, s.frozenPos.getY(), s.frozenPos.getZ() + 0.5);
                    }
                    p.setDeltaMovement(0, 0, 0);
                }
            }
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> sessions.containsKey(player.getUUID()) ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) -> sessions.containsKey(player.getUUID()) ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> sessions.containsKey(player.getUUID()) ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> sessions.containsKey(player.getUUID()) ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> sessions.containsKey(player.getUUID()) ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> !sessions.containsKey(player.getUUID()));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> !sessions.containsKey(sender.getUUID()));
    }

    private void registerNetworking() {
        ServerPlayNetworking.registerGlobalReceiver(ChallengeC2SPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            CheckSession s = sessions.get(player.getUUID());
            if (s == null) return;
            String expected = solveChallenge(s.challenge);
            if (!expected.equals(payload.response())) {
                kick(player, "Client verification failed (integrity proof mismatch).");
                return;
            }
            ServerPlayNetworking.send(player, new ModListRequestPayload("login"));
        });
        ServerPlayNetworking.registerGlobalReceiver(ModListPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!payload.reason().equals("login") && !payload.reason().startsWith("wl:")) return;
            if (payload.reason().equals("login")) {
                verifyAgainstWhitelist(player, payload.mods());
            } else {
                UUID target = UUID.fromString(payload.reason().substring(3));
                pendingSelections.put(target, payload.mods());
                ServerPlayer targetPlayer = currentServer.getPlayerList().getPlayer(target);
                if (targetPlayer != null) {
                    targetPlayer.sendSystemMessage(Component.literal("§b[B40] Danh sách mod đã quét:"));
                    for (int i = 0; i < payload.mods().size(); i++) {
                        ModFingerprint m = payload.mods().get(i);
                        targetPlayer.sendSystemMessage(Component.literal("§7" + (i + 1) + ". §f" + m.id() + " §8(" + m.version() + ")"));
                    }
                    targetPlayer.sendSystemMessage(Component.literal("§aDùng: /addwl add " + targetPlayer.getName().getString() + " 1 2 3 hoặc all"));
                }
            }
        });
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("addwl")
                                                .then(Commands.literal("scan")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(this::scanPlayerMods)))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("indexes", StringArgumentType.greedyString())
                                                .executes(this::addSelectedMods))))
        ));
    }

    private int scanPlayerMods(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sender) || !isOp(sender)) return 0;
        String name = StringArgumentType.getString(ctx, "player");
        ServerPlayer player = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (player == null) return 0;
        pendingSelections.remove(player.getUUID());
        ServerPlayNetworking.send(player, new ModListRequestPayload("wl:" + player.getUUID()));
        ctx.getSource().sendSuccess(() -> Component.literal("Requested mod list from " + name + ". Run /addwl add " + name + " <indexes|all> after list arrives."), false);
        return 1;
    }

    private int addSelectedMods(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sender) || !isOp(sender)) return 0;
        String name = StringArgumentType.getString(ctx, "player");
        String indexes = StringArgumentType.getString(ctx, "indexes");
        ServerPlayer player = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (player == null) return 0;
        List<ModFingerprint> mods = pendingSelections.get(player.getUUID());
        if (mods == null || mods.isEmpty()) return 0;
        List<ModFingerprint> picked = new ArrayList<>();
        if (indexes.equalsIgnoreCase("all")) picked.addAll(mods);
        else {
            for (String token : indexes.split("\\s+")) {
                int idx = Integer.parseInt(token) - 1;
                if (idx >= 0 && idx < mods.size()) picked.add(mods.get(idx));
            }
        }
        for (ModFingerprint m : picked) {
            whitelist.add(new WhitelistEntry(m.id(), m.version(), m.hash()));
        }
        saveWhitelist();
        ctx.getSource().sendSuccess(() -> Component.literal("Added " + picked.size() + " entries to whitelist."), true);
        return 1;
    }

    private void beginCheck(ServerPlayer player) {
        if (isOp(player)) return;
        String challenge = UUID.randomUUID() + ":" + System.nanoTime();
        CheckSession s = new CheckSession(challenge, player.blockPosition());
        sessions.put(player.getUUID(), s);
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 60, 1, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20 * 60, 6, false, false));
        player.displayClientMessage(Component.literal("§6[Security] Đang xác minh client, vui lòng chờ..."), true);
        ServerPlayNetworking.send(player, new ChallengeS2CPayload(challenge));
    }

    private void verifyAgainstWhitelist(ServerPlayer player, List<ModFingerprint> mods) {
        if (isOp(player)) {
            sessions.remove(player.getUUID());
            return;
        }
        Set<String> allowed = new HashSet<>();
        for (WhitelistEntry e : whitelist) allowed.add(e.id() + "|" + e.version() + "|" + e.hash());
        for (ModFingerprint mod : mods) {
            String key = mod.id() + "|" + mod.version() + "|" + mod.hash();
            if (!allowed.contains(key)) {
                kick(player, "Unauthorized client modification detected: " + mod.id() + " " + mod.version());
                return;
            }
        }
        sessions.remove(player.getUUID());
        player.removeEffect(MobEffects.BLINDNESS);
        player.removeEffect(MobEffects.SLOWNESS);
        player.displayClientMessage(Component.literal("§a[Security] Xác minh hoàn tất. Chúc bạn chơi vui!"), false);
    }

    private static String solveChallenge(String input) {
        byte[] data = input.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < CHAIN_ROUNDS; i++) data = sha(data, STATIC_SALT, new byte[]{(byte) i});
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] sha(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) digest.update(p);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void loadWhitelist() {
        whitelist.clear();
        try {
            Files.createDirectories(whitelistPath.getParent());
            if (!Files.exists(whitelistPath)) {
                Files.writeString(whitelistPath, "[]");
            }
            whitelist.addAll(GSON.fromJson(Files.readString(whitelistPath), WL_TYPE));
        } catch (IOException e) {
            throw new RuntimeException("Unable to load whitelist", e);
        }
    }

    private void saveWhitelist() {
        try {
            Files.writeString(whitelistPath, GSON.toJson(whitelist));
        } catch (IOException e) {
            throw new RuntimeException("Unable to save whitelist", e);
        }
    }

    private boolean isOp(ServerPlayer player) {
        if (currentServer == null) return false;

        Path ops = currentServer.getFile("ops.json");
        if (!Files.exists(ops)) return false;
        try {
            String content = Files.readString(ops);
            return content.contains(player.getStringUUID()) || content.contains("\"name\": \"" + player.getName().getString() + "\"");
        } catch (IOException e) {
            return false;
        }
    }

    private void kick(ServerPlayer player, String reason) {
        sessions.remove(player.getUUID());
        player.connection.disconnect(Component.literal("§cB40 Security Gateway\n§7Connection terminated by server policy.\n§fReason: §e" + reason + "\n§8If you believe this is a mistake, contact server staff."));
    }

    public record CheckSession(String challenge, BlockPos frozenPos) {}
    public record WhitelistEntry(String id, String version, String hash) {}
    public record ModFingerprint(String id, String version, String hash) {}

    public record ChallengeS2CPayload(String challenge) implements CustomPacketPayload {
        public static final Type<ChallengeS2CPayload> TYPE = new Type<>(CHALLENGE_S2C_ID);
        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, ChallengeS2CPayload> CODEC =
                net.minecraft.network.codec.StreamCodec.of((buf, payload) -> buf.writeUtf(payload.challenge), buf -> new ChallengeS2CPayload(buf.readUtf()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record ChallengeC2SPayload(String response) implements CustomPacketPayload {
        public static final Type<ChallengeC2SPayload> TYPE = new Type<>(CHALLENGE_C2S_ID);
        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, ChallengeC2SPayload> CODEC =
                net.minecraft.network.codec.StreamCodec.of((buf, payload) -> buf.writeUtf(payload.response), buf -> new ChallengeC2SPayload(buf.readUtf()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record ModListRequestPayload(String reason) implements CustomPacketPayload {
        public static final Type<ModListRequestPayload> TYPE = new Type<>(MODLIST_REQUEST_S2C_ID);
        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, ModListRequestPayload> CODEC =
                net.minecraft.network.codec.StreamCodec.of((buf, payload) -> buf.writeUtf(payload.reason), buf -> new ModListRequestPayload(buf.readUtf()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record ModListPayload(String reason, List<ModFingerprint> mods) implements CustomPacketPayload {
        public static final Type<ModListPayload> TYPE = new Type<>(MODLIST_C2S_ID);
        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, ModListPayload> CODEC =
                net.minecraft.network.codec.StreamCodec.of((buf, payload) -> {
                    buf.writeUtf(payload.reason);
                    buf.writeInt(payload.mods.size());
                    for (ModFingerprint mod : payload.mods) {
                        buf.writeUtf(mod.id); buf.writeUtf(mod.version); buf.writeUtf(mod.hash);
                    }
                }, buf -> {
                    String reason = buf.readUtf(); int size = buf.readInt(); List<ModFingerprint> mods = new ArrayList<>();
                    for (int i = 0; i < size; i++) mods.add(new ModFingerprint(buf.readUtf(), buf.readUtf(), buf.readUtf()));
                    return new ModListPayload(reason, mods);
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}