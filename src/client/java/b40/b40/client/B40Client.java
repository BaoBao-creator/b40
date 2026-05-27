package b40.b40.client;

import b40.b40.AntiCheatManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class B40Client implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(AntiCheatManager.ChallengeS2CPayload.TYPE, (payload, context) -> {
            String solved = solveChallenge(payload.challenge());
            ClientPlayNetworking.send(new AntiCheatManager.ChallengeC2SPayload(solved));
        });

        ClientPlayNetworking.registerGlobalReceiver(AntiCheatManager.ModListRequestPayload.TYPE, (payload, context) -> {
            ClientPlayNetworking.send(new AntiCheatManager.ModListPayload(payload.reason(), scanMods()));
        });
    }

    private static List<AntiCheatManager.ModFingerprint> scanMods() {
        List<AntiCheatManager.ModFingerprint> out = new ArrayList<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            ModMetadata meta = container.getMetadata();
            String hash = hashPaths(container.getRootPaths());
            out.add(new AntiCheatManager.ModFingerprint(meta.getId(), meta.getVersion().getFriendlyString(), hash));
        }
        return out;
    }

    private static String hashPaths(List<Path> paths) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path p : paths) {
                if (Files.isRegularFile(p)) digest.update(Files.readAllBytes(p));
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            return "ERR_HASH";
        }
    }

    private static String solveChallenge(String input) {
        byte[] data = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] salt = "b40-v1-ac".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < 5; i++) data = sha(data, salt, new byte[]{(byte) i});
        return Base64.getEncoder().encodeToString(data);
    }

    private static byte[] sha(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) digest.update(p);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
