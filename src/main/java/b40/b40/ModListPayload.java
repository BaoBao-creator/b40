package b40.b40;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record ModListPayload(String token, List<ModEntry> mods) implements CustomPacketPayload {
    public static final int MAX_MOD_ENTRIES = 4096;
    public static final Type<ModListPayload> ID = new Type<>(Identifier.fromNamespaceAndPath(B40.MOD_ID, "mod_list"));

    public static final StreamCodec<FriendlyByteBuf, ModListPayload> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.token(), 256);
                buf.writeVarInt(value.mods().size());
                for (ModEntry entry : value.mods()) {
                    buf.writeUtf(entry.modId(), 256);
                    buf.writeUtf(entry.fileName(), 512);
                    buf.writeUtf(entry.sha256(), 128);
                }
            },
            buf -> {
                String token = buf.readUtf(256);
                int size = buf.readVarInt();
                if (size < 0 || size > MAX_MOD_ENTRIES) {
                    throw new IllegalArgumentException("Invalid mod list size: " + size);
                }
                List<ModEntry> mods = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    String modId = buf.readUtf(256);
                    String fileName = buf.readUtf(512);
                    String sha256 = buf.readUtf(128);
                    mods.add(new ModEntry(modId, fileName, sha256));
                }
                return new ModListPayload(token, mods);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record ModEntry(String modId, String fileName, String sha256) {
    }
}
