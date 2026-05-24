package b40.b40.client;

import b40.b40.B40;
import b40.b40.B40Server;
import b40.b40.ModListPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
    private static final int SEND_RETRY_COUNT = 5;
    private static final long SEND_RETRY_INTERVAL_MS = 750L;
    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                List<ModListPayload.ModEntry> mods = collectMods();
                sendAttestationWithRetry(client, mods, 0);
            } catch (Exception ex) {
                B40.LOGGER.error("Cannot prepare mod list payload", ex);
            }
        });
    }


    private static void sendAttestationWithRetry(net.minecraft.client.Minecraft client, List<ModListPayload.ModEntry> mods, int attempt) {
        if (!ClientPlayNetworking.canSend(ModListPayload.ID)) {
            if (attempt >= SEND_RETRY_COUNT) {
                B40.LOGGER.error("Cannot send mod list payload: channel unavailable after {} attempts", attempt + 1);
                return;
            }
            try {
                Thread.sleep(SEND_RETRY_INTERVAL_MS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            client.execute(() -> sendAttestationWithRetry(client, mods, attempt + 1));
            return;
        }

        try {
            String token = B40Server.createCurrentToken(System.currentTimeMillis());
            ClientPlayNetworking.send(new ModListPayload(token, mods));
            B40.LOGGER.info("Sent b40 attestation payload on attempt {}", attempt + 1);
        } catch (Exception ex) {
            if (attempt >= SEND_RETRY_COUNT) {
                B40.LOGGER.error("Cannot send mod list payload after {} attempts", attempt + 1, ex);
                return;
            }
            B40.LOGGER.warn("b40 payload send failed on attempt {}, retrying", attempt + 1, ex);
            try {
                Thread.sleep(SEND_RETRY_INTERVAL_MS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            client.execute(() -> sendAttestationWithRetry(client, mods, attempt + 1));
        }
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
