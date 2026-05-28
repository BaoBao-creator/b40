package b40.b40.client;

import b40.b40.AntiCheatManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) return out;
        try (Stream<Path> paths = Files.walk(modsDir)) {
            paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(path -> out.add(readModFingerprint(path)));
        } catch (IOException ignored) {
        }
        return out;
    }

    private static AntiCheatManager.ModFingerprint readModFingerprint(Path jarPath) {
        String id = jarPath.getFileName().toString();
        String version = "unknown";
        String hash = hashFile(jarPath);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry fabricModJson = jar.getJarEntry("fabric.mod.json");
            if (fabricModJson != null) {
                try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(fabricModJson), java.nio.charset.StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    if (json.has("id")) id = json.get("id").getAsString();
                    if (json.has("version")) version = json.get("version").getAsString();
                }
            }
        } catch (IOException ignored) {
            // keep filename/unknown fallback
        }

        return new AntiCheatManager.ModFingerprint(id, version, hash);
    }

    private static String hashFile(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
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
