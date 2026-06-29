package io.bitken.ss.cli.conf.ds;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * De-risk unit tests for plan-90 Task 1: the hand-rolled emitter must produce
 * {@code [[projects]]} array-of-tables text and Jackson must read it back equivalently.
 */
class ArrayOfTablesTomlEmitterTest {

    private static StandaloneConfig.ProjectEntry entry(
            String remoteUrl, String localPath, String storageRoot, String storageType) {
        StandaloneConfig.ProjectEntry e = new StandaloneConfig.ProjectEntry();
        e.setRemoteUrl(remoteUrl);
        e.setLocalPath(localPath);
        e.setStorageRoot(storageRoot);
        e.setStorageType(storageType);
        return e;
    }

    @Test
    void emitsBlockPerProject_omittingAbsentKeys() {
        StandaloneConfig cfg = new StandaloneConfig();
        cfg.setProjects(List.of(
                entry("git@github.com:x/audio-gen.git", "/home/p/audio-gen", "/home/p/audio-gen-ss", "filesystem"),
                entry("git@github.com:x/ss-toml.git", "/home/p/ss-toml", null, "embedded")));

        String toml = new ArrayOfTablesTomlEmitter().emit(cfg);

        // One header per project.
        assertEquals(2, toml.lines().filter(l -> l.equals("[[projects]]")).count(), toml);
        // Filesystem entry emits storageRoot; embedded omits it (exactly one storageRoot line).
        assertEquals(1, toml.lines().filter(l -> l.startsWith("storageRoot = ")).count(), toml);
        // Literal single-quoted strings for ordinary values.
        assertTrue(toml.contains("localPath = '/home/p/audio-gen'"), toml);
        assertTrue(toml.contains("storageType = 'embedded'"), toml);
    }

    @Test
    void roundTripsThroughJackson() throws IOException {
        StandaloneConfig cfg = new StandaloneConfig();
        cfg.setProjects(List.of(
                entry("git@github.com:x/audio-gen.git", "/home/p/audio-gen", "/home/p/audio-gen-ss", "filesystem"),
                entry(null, "/home/p/ss-toml", null, "embedded")));

        String toml = new ArrayOfTablesTomlEmitter().emit(cfg);
        StandaloneConfig back = new TomlMapper().readValue(toml, StandaloneConfig.class);

        assertEquals(2, back.getProjects().size());
        StandaloneConfig.ProjectEntry ext = back.getProjects().get(0);
        assertEquals("/home/p/audio-gen", ext.getLocalPath());
        assertEquals("/home/p/audio-gen-ss", ext.getStorageRoot());
        assertEquals("filesystem", ext.getStorageType());
        StandaloneConfig.ProjectEntry in = back.getProjects().get(1);
        assertEquals("/home/p/ss-toml", in.getLocalPath());
        assertNull(in.getStorageRoot(), "embedded entry must not round-trip a storageRoot");
    }

    @Test
    void escapesValueContainingSingleQuote() throws IOException {
        StandaloneConfig cfg = new StandaloneConfig();
        cfg.setProjects(List.of(
                entry(null, "/home/p/wei'rd", "/home/p/wei'rd-ss", "filesystem")));

        String toml = new ArrayOfTablesTomlEmitter().emit(cfg);
        // A value with a single quote cannot use a literal string; round-trip proves correctness.
        StandaloneConfig back = new TomlMapper().readValue(toml, StandaloneConfig.class);
        assertEquals("/home/p/wei'rd", back.getProjects().get(0).getLocalPath(), toml);
    }

    @Test
    void escapesBasicStringSpecials_andRoundTrips() throws IOException {
        // A double-quote forces the basic-string branch; each special must escape correctly.
        String raw = "a\"b\\c\td\re\nf";
        StandaloneConfig cfg = new StandaloneConfig();
        cfg.setProjects(List.of(entry(null, raw, null, "filesystem")));

        String toml = new ArrayOfTablesTomlEmitter().emit(cfg);
        assertTrue(toml.contains("localPath = \""), "value with a quote must use a basic string:\n" + toml);

        StandaloneConfig back = new TomlMapper().readValue(toml, StandaloneConfig.class);
        assertEquals(raw, back.getProjects().get(0).getLocalPath(),
                "all basic-string escapes must round-trip:\n" + toml);
    }

    @Test
    void emptyConfigReadsBackEmpty() throws IOException {
        String toml = new ArrayOfTablesTomlEmitter().emit(new StandaloneConfig());
        StandaloneConfig back = new TomlMapper().readValue(toml, StandaloneConfig.class);
        assertTrue(back.getProjects().isEmpty(), "empty config must read back with no projects");
    }
}
