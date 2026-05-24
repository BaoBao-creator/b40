package b40.b40;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record ModListPayload(String token, List<ModEntry> mods) implements CustomPayload {
    public static final int MAX_MOD_ENTRIES = 4096;
    public static final Id<ModListPayload> ID = new Id<>(Identifier.of(B40.MOD_ID, "mod_list"));

    public static final PacketCodec<PacketByteBuf, ModListPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.token(), 256);
                buf.writeVarInt(value.mods().size());
                for (ModEntry entry : value.mods()) {
                    buf.writeString(entry.modId(), 256);
                    buf.writeString(entry.fileName(), 512);
                    buf.writeString(entry.sha256(), 128);
                }
            },
            buf -> {
                String token = buf.readString(256);
                int size = buf.readVarInt();
                if (size < 0 || size > MAX_MOD_ENTRIES) {
                    throw new IllegalArgumentException("Invalid mod list size: " + size);
                }
                List<ModEntry> mods = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    String modId = buf.readString(256);
                    String fileName = buf.readString(512);
                    String sha256 = buf.readString(128);
                    mods.add(new ModEntry(modId, fileName, sha256));
                }
                return new ModListPayload(token, mods);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record ModEntry(String modId, String fileName, String sha256) {
    }
}
