package b40.b40.client;

import b40.b40.B40;
import b40.b40.B40Server;
import b40.b40.ModListPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class B40Client implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playC2S().register(ModListPayload.ID, ModListPayload.CODEC);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!ClientPlayNetworking.canSend(ModListPayload.ID)) {
                B40.LOGGER.debug("Server does not support b40 payload, skipping mod list send");
                return;
            }

            try {
                String token = B40Server.createCurrentToken(System.currentTimeMillis());
                List<ModListPayload.ModEntry> mods = collectMods();
                ClientPlayNetworking.send(new ModListPayload(token, mods));
            } catch (Exception ex) {
                B40.LOGGER.error("Cannot send mod list payload", ex);
            }
        });
    }

    private static List<ModListPayload.ModEntry> collectMods() {
        List<ModListPayload.ModEntry> entries = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            List<Path> originPaths = mod.getOrigin().getPaths();
            if (originPaths.isEmpty()) {
                continue;
            }

            Path selected = originPaths.stream()
                    .filter(Files::isRegularFile)
                    .min(Comparator.comparingInt(path -> path.getFileName().toString().length()))
                    .orElse(originPaths.get(0));

            String hash = safeFileSha256(selected);
            String fileName = selected.getFileName() == null ? selected.toString() : selected.getFileName().toString();
            entries.add(new ModListPayload.ModEntry(mod.getMetadata().getId(), fileName, hash));
        }
        return entries;
    }

    private static String safeFileSha256(Path file) {
        try (InputStream stream = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return B40Server.toHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            B40.LOGGER.warn("Failed to hash file {}", file, e);
            return "ERROR";
        }
    }
}
