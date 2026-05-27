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
import net.minecraft.world.level.Level;

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

        int nextMode = (PLAYER_MODES.getOrDefault(player.getUUID(), -1) + 1) % MODES.length;
        PLAYER_MODES.put(player.getUUID(), nextMode);

        player.displayClientMessage(
                Component.literal("Admin mode: ").append(Component.literal(MODES[nextMode]).withStyle(ChatFormatting.GOLD)),
                true);
        return InteractionResult.CONSUME;
    }
}
