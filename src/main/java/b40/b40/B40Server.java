package b40.b40;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class B40Server {
    private static final long TIME_WINDOW_MS = 30_000L;
    private static final long FORCE_INSTALL_TIMEOUT_MS = 20_000L;
    private static final int TOKEN_ACCEPTANCE_WINDOWS = 2;
    private static final DateTimeFormatter LOG_FILE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int MAX_PLAYER_DIR_LENGTH = 40;

    private static final Map<UUID, Long> pendingPlayers = new ConcurrentHashMap<>();

    private B40Server() {
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(ModListPayload.ID, ModListPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ModListPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isValidToken(payload.token())) {
                B40.LOGGER.warn("Disconnecting {}: invalid b40 token", player.getName().getString());
                player.connection.disconnect(Component.literal("Mã xác thực không hợp lệ!"));
                return;
            }

            pendingPlayers.remove(player.getUUID());
            B40.LOGGER.info("Accepted b40 attestation from {}", player.getName().getString());
            logPlayerMods(player, payload);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                pendingPlayers.put(handler.player.getUUID(), System.currentTimeMillis()));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                pendingPlayers.remove(handler.player.getUUID()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.currentTimeMillis();
            pendingPlayers.entrySet().removeIf(entry -> {
                if ((now - entry.getValue()) < FORCE_INSTALL_TIMEOUT_MS) {
                    return false;
                }

                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    long waited = now - entry.getValue();
                    B40.LOGGER.warn("Disconnecting {}: no b40 payload after {} ms", player.getName().getString(), waited);
                    player.connection.disconnect(Component.literal("Mất kết nối với máy chủ (Thiếu mod b40)"));
                }
                return true;
            });
        });

        B40.LOGGER.info("B40 server security initialized");
    }

    public static String createCurrentToken(long timeMillis) {
        long timeBlock = timeMillis / TIME_WINDOW_MS;
        String base = timeBlock + new String(obfuscatedSecret(), StandardCharsets.UTF_8);
        return sha256String(base);
    }

    private static boolean isValidToken(String clientToken) {
        long now = System.currentTimeMillis();
        for (int offset = 0; offset <= TOKEN_ACCEPTANCE_WINDOWS; offset++) {
            if (createCurrentToken(now - (offset * TIME_WINDOW_MS)).equals(clientToken)) {
                return true;
            }
        }
        return false;
    }

    private static void logPlayerMods(ServerPlayer player, ModListPayload payload) {
        try {
            Path root = player.level().getServer().getServerDirectory();
            String safePlayerDir = sanitizePlayerDirectoryName(player.getName().getString(), player.getUUID());
            Path playerDir = root.resolve("playermods").resolve(safePlayerDir);
            Files.createDirectories(playerDir);

            Path outFile = playerDir.resolve(LocalDateTime.now().format(LOG_FILE_NAME) + ".txt");
            StringBuilder builder = new StringBuilder();
            builder.append("player=").append(player.getName().getString()).append('\n');
            builder.append("token=").append(payload.token()).append('\n');
            builder.append("mods=").append(payload.mods().size()).append("\n\n");

            for (ModListPayload.ModEntry entry : payload.mods()) {
                builder.append(entry.modId())
                        .append(" | ")
                        .append(entry.fileName())
                        .append(" | ")
                        .append(entry.sha256())
                        .append('\n');
            }

            Files.writeString(outFile, builder.toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            B40.LOGGER.error("Failed writing client mod list for {}", player.getName().getString(), exception);
        }
    }

    static byte[] obfuscatedSecret() {
        byte[] a = new byte[]{98, 52, 48, 95, 99, 111};
        byte[] b = new byte[]{114, 101, 95, 115, 101, 99};
        byte[] c = new byte[]{117, 114, 101, 95, 107, 101, 121};
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }

    static String sha256String(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static String sanitizePlayerDirectoryName(String rawName, UUID fallbackUuid) {
        String normalized = rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        normalized = normalized.replaceAll("_+", "_").replaceAll("^[_\\.-]+|[_\\.-]+$", "");

        if (normalized.isBlank()) {
            normalized = "player";
        }

        if (normalized.length() > MAX_PLAYER_DIR_LENGTH) {
            normalized = normalized.substring(0, MAX_PLAYER_DIR_LENGTH);
        }

        return normalized + "_" + fallbackUuid.toString().substring(0, 8);
    }
}
