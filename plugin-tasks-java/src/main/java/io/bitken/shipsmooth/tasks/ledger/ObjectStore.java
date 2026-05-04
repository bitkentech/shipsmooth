package io.bitken.shipsmooth.tasks.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public class ObjectStore {

    private final Path root;

    public ObjectStore(Path repoRoot) {
        this.root = repoRoot.resolve(".agents").resolve("objects");
    }

    public String writeObject(byte[] bytes) throws IOException {
        byte[] header = ("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8);
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e);
        }
        sha1.update(header);
        sha1.update(bytes);
        String hash = HexFormat.of().formatHex(sha1.digest());

        Path objDir = root.resolve(hash.substring(0, 2));
        Files.createDirectories(objDir);
        Path target = objDir.resolve(hash.substring(2));
        if (Files.exists(target)) {
            return hash;
        }
        Path tmp = Files.createTempFile(objDir, ".tmp-", ".obj");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            if (Files.exists(target)) {
                return hash;
            }
            throw e;
        }
        return hash;
    }

    public byte[] readObject(String sha1) throws IOException {
        if (sha1.length() == 40) {
            return Files.readAllBytes(root.resolve(sha1.substring(0, 2)).resolve(sha1.substring(2)));
        }
        // Prefix lookup: scan the fan-out directory for a unique match.
        String dir = sha1.substring(0, Math.min(2, sha1.length()));
        String remainder = sha1.length() > 2 ? sha1.substring(2) : "";
        Path fanDir = root.resolve(dir);
        if (!Files.isDirectory(fanDir)) {
            throw new IOException("No object with prefix: " + sha1);
        }
        try (var entries = Files.list(fanDir)) {
            List<Path> matches = entries
                    .filter(p -> p.getFileName().toString().startsWith(remainder))
                    .toList();
            if (matches.isEmpty()) throw new IOException("No object with prefix: " + sha1);
            if (matches.size() > 1) throw new IOException("Ambiguous SHA prefix: " + sha1);
            return Files.readAllBytes(matches.get(0));
        }
    }
}