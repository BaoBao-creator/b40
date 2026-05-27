package b40.b40.item;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;

public class AdminWandItem extends Item {
    private static final String[] MODES = {"kill", "kick", "ban", "ban ip"};
    private static final Map<UUID, Integer> PLAYER_MODES = new ConcurrentHashMap<>();

    public AdminWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!player.canUseGameMasterBlocks()) {
            player.displayClientMessage(
                    Component.literal("You must be an operator to use this item.").withStyle(ChatFormatting.RED),
                    true);
            return InteractionResult.FAIL;
        }

        cycleMode(player);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, net.minecraft.world.entity.LivingEntity interactionTarget, InteractionHand usedHand) {
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!player.canUseGameMasterBlocks() || !(player instanceof ServerPlayer serverPlayer)) {
            player.displayClientMessage(Component.literal("You must be an operator to use this item.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        if (!(interactionTarget instanceof ServerPlayer target)) {
            player.displayClientMessage(Component.literal("Target must be a player.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        int mode = PLAYER_MODES.getOrDefault(player.getUUID(), 0);
        String command = buildCommand(MODES[mode], target);
        var server = serverPlayer.level().getServer();
        if (server == null) {
            player.displayClientMessage(Component.literal("Cannot execute command: server unavailable.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }
        server.getCommands().performPrefixedCommand(serverPlayer.createCommandSourceStack().withSuppressedOutput(), command);
        player.displayClientMessage(Component.literal("Executed: /" + command).withStyle(ChatFormatting.GREEN), true);
        return InteractionResult.CONSUME;
    }

    private static String buildCommand(String mode, ServerPlayer target) {
        String targetName = target.getName().getString();
        return switch (mode) {
            case "kill" -> "kill " + targetName;
            case "kick" -> "kick " + targetName + " Removed by admin wand.";
            case "ban" -> "ban " + targetName + " Banned by admin wand.";
            case "ban ip" -> "ban-ip " + target.getIpAddress() + " Banned by admin wand.";
            default -> "";
        };
    }

    private static void cycleMode(Player player) {
        int nextMode = (PLAYER_MODES.getOrDefault(player.getUUID(), -1) + 1) % MODES.length;
        PLAYER_MODES.put(player.getUUID(), nextMode);
        player.displayClientMessage(Component.literal("Admin mode: ").append(Component.literal(MODES[nextMode]).withStyle(ChatFormatting.GOLD)), true);
    }
}
