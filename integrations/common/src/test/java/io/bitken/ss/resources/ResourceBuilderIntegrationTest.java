package io.bitken.ss.resources;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResourceBuilderIntegrationTest {

    @TempDir
    Path tempDir;

    private static final List<String> PROPS = List.of(
        "build.outputDir", "build.env", "build.platform", "plugin.base.name",
        "plugin.skill.start.basename", "plugin.version", "plugin.description",
        "skill.frontmatter", "shipsmooth.jlink.dir", "experimental.enabled", "plugin.hook.command",
        "plugin.repo.name"
    );

    @AfterEach
    void clearProps() {
        PROPS.forEach(System::clearProperty);
    }

    @Test
    void skillMdIsRenderedForDevProfile() throws Exception {
        setDevProps();
        ResourceBuilder.main(new String[]{});

        Path output = tempDir.resolve("skills/start-dev/SKILL.md");
        assertTrue(Files.exists(output), "SKILL.md should be written");

        String content = Files.readString(output);
        assertTrue(content.contains("# start-dev — Agent Coding Workflow"),
            "Claude profile should contain heading");
        assertFalse(content.stripLeading().startsWith("---"),
            "Claude profile should not start with YAML frontmatter");
        assertTrue(content.contains("${XDG_CACHE_HOME:-~/.cache}/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth"),
            "CLI bin path should use XDG shell expression with -dev subdir");
    }

    @Test
    void hooksJsonIsRenderedForDevProfile() throws Exception {
        setDevProps();
        ResourceBuilder.main(new String[]{});

        Path output = tempDir.resolve("hooks/hooks.json");
        assertTrue(Files.exists(output), "hooks.json should be written");

        String content = Files.readString(output);
        assertTrue(content.contains("session-start.js"), "command should invoke session-start.js");
        assertTrue(content.contains("CLAUDE_PLUGIN_ROOT"), "command should reference CLAUDE_PLUGIN_ROOT");
    }

    @Test
    void hooksJsonIsRenderedForProdProfile() throws Exception {
        setProdProps();
        ResourceBuilder.main(new String[]{});

        Path output = tempDir.resolve("hooks/hooks.json");
        assertTrue(Files.exists(output), "hooks.json should be written");

        String content = Files.readString(output);
        assertFalse(content.contains("shipsmooth-dev"), "prod command should not reference dev cache dir");
        assertTrue(content.contains("session-start.js"), "command should invoke session-start.js");
    }

    @Test
    void sessionStartConfigIsWrittenForDevProfile() throws Exception {
        setDevProps();
        ResourceBuilder.main(new String[]{});

        Path output = tempDir.resolve("dist/session-start-config.json");
        assertTrue(Files.exists(output), "session-start-config.json should be written");

        String content = Files.readString(output);
        assertTrue(content.contains("\"version\" : \"0.2.0\""), "config should contain version");
        assertTrue(content.contains("\"name\" : \"shipsmooth-dev\""), "dev config should contain plugin name for cache subdir resolution");
        assertFalse(content.contains("cacheDir"), "config must not contain cacheDir");
    }

    @Test
    void skillMdIsRenderedForGeminiProfile() throws Exception {
        String frontmatter = "---\nname: start\ndescription: Use when starting any task — applies the shipsmooth agent coding workflow.\n---\n\n";

        System.setProperty("build.outputDir", tempDir.toString());
        System.setProperty("build.env", "prod");
        System.setProperty("build.platform", "gemini");
        System.setProperty("plugin.base.name", "shipsmooth");
        System.setProperty("plugin.skill.start.basename", "start");
        System.setProperty("plugin.version", "0.2.0");
        System.setProperty("plugin.description", "Agent coding workflow");
        System.setProperty("skill.frontmatter", frontmatter);
        System.setProperty("shipsmooth.jlink.dir", "");
        System.setProperty("experimental.enabled", "false");

        ResourceBuilder.main(new String[]{});

        Path output = tempDir.resolve("skills/start/SKILL.md");
        assertTrue(Files.exists(output), "SKILL.md should be written");

        String content = Files.readString(output);
        assertTrue(content.startsWith("---\nname: start"),
            "Gemini profile should start with YAML frontmatter");
        assertTrue(content.contains("# start — Agent Coding Workflow"),
            "Heading should follow frontmatter");
    }

    @Test
    void parallelContentIsRemovedFromBaseSkill() throws Exception {
        setDevProps();
        ResourceBuilder.main(new String[]{});

        Path baseSkill = tempDir.resolve("skills/start-dev/SKILL.md");
        assertTrue(Files.exists(baseSkill), "base SKILL.md should be written");

        String content = Files.readString(baseSkill);
        assertFalse(content.contains("## Parallel Execution Protocol"),
            "base start-dev skill must not contain the Parallel Execution Protocol section "
                + "(it should live only in experimental-start-parallel-dev)");
        assertFalse(content.contains("Worker Instruction Block"),
            "base start-dev skill must not contain the Worker Instruction Block");
    }

    @Test
    void experimentalParallelSkillIsRendered() throws Exception {
        setDevProps();
        ResourceBuilder.main(new String[]{});

        Path parallelSkill = tempDir.resolve("skills/experimental-start-parallel-dev/SKILL.md");
        assertTrue(Files.exists(parallelSkill),
            "experimental-start-parallel-dev/SKILL.md should be rendered");

        String content = Files.readString(parallelSkill);
        assertTrue(content.contains("## Core Invariants"),
            "experimental parallel skill should include the base workflow content");
        assertTrue(content.contains("## Parallel Execution Protocol"),
            "experimental parallel skill should include the parallel section");
        assertTrue(content.contains("--enable-experimental"),
            "experimental parallel skill should call the CLI with --enable-experimental");
    }

    @Test
    void sessionStartConfigForProdContainsName() throws Exception {
        setProdProps();
        ResourceBuilder.main(new String[]{});

        Path output = tempDir.resolve("dist/session-start-config.json");
        assertTrue(Files.exists(output), "session-start-config.json should be written");

        String content = Files.readString(output);
        assertTrue(content.contains("\"name\" : \"shipsmooth\""), "prod config should contain plugin name for cache subdir resolution");
        assertFalse(content.contains("cacheDir"), "config must not contain cacheDir");
    }

    @Test
    void cliBinInSkillMdUsesProdSubdir() throws Exception {
        setProdProps();
        ResourceBuilder.main(new String[]{});

        String content = Files.readString(tempDir.resolve("skills/start/SKILL.md"));
        assertTrue(content.contains("${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-0.2.0/bin/shipsmooth"),
            "prod cliBin should use shipsmooth subdir");
        assertFalse(content.contains("shipsmooth-dev"),
            "prod cliBin must not reference shipsmooth-dev");
    }

    @Test
    void cliBinInSkillMdUsesDevSubdir() throws Exception {
        setDevProps();
        ResourceBuilder.main(new String[]{});

        String content = Files.readString(tempDir.resolve("skills/start-dev/SKILL.md"));
        assertTrue(content.contains("${XDG_CACHE_HOME:-~/.cache}/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth"),
            "dev cliBin should use shipsmooth-dev subdir");
    }

    @Test
    void hooksJsonForWindowsProfile_usesCmdExeXcopy() throws Exception {
        setWindowsProps();
        ResourceBuilder.main(new String[]{});

        Path output = tempDir.resolve("hooks/hooks.json");
        assertTrue(Files.exists(output), "hooks.json should be written");

        String content = Files.readString(output);
        assertTrue(content.contains("cmd.exe"), "Windows hook must use cmd.exe");
        assertTrue(content.contains("0.3.10"), "Windows hook must contain version");
        assertTrue(content.contains("shipsmooth-windows"), "hook source path must use repo name (shipsmooth-windows)");
        assertFalse(content.contains("session-start.js"), "Windows hook must not reference session-start.js");
        assertFalse(content.contains("CLAUDE_PLUGIN_ROOT"), "Windows hook must not reference CLAUDE_PLUGIN_ROOT");

        String bat = Files.readString(tempDir.resolve("hooks/install-runtime.bat"));
        assertTrue(bat.contains("xcopy"), "install-runtime.bat must use xcopy");
        assertTrue(bat.contains("LOCALAPPDATA"), "install-runtime.bat must reference LOCALAPPDATA");
        assertTrue(bat.contains("\\shipsmooth\\"), "install-runtime.bat destination must use plugin name (shipsmooth)");
    }

    @Test
    void skillMdForWindowsProfile_usesLocalAppDataPath() throws Exception {
        setWindowsProps();
        ResourceBuilder.main(new String[]{});

        String content = Files.readString(tempDir.resolve("skills/start/SKILL.md"));
        assertTrue(content.contains("%LOCALAPPDATA%\\shipsmooth\\0.3.10\\runtime\\bin\\shipsmooth.bat"),
            "Windows SKILL.md must reference LOCALAPPDATA stable path");
        assertFalse(content.contains("XDG_CACHE_HOME"),
            "Windows SKILL.md must not reference XDG_CACHE_HOME");
    }

    private void setProdProps() {
        System.setProperty("build.outputDir", tempDir.toString());
        System.setProperty("build.env", "prod");
        System.setProperty("build.platform", "claude");
        System.setProperty("plugin.base.name", "shipsmooth");
        System.setProperty("plugin.skill.start.basename", "start");
        System.setProperty("plugin.version", "0.2.0");
        System.setProperty("plugin.description", "Agent coding workflow");
        System.setProperty("skill.frontmatter", "");
        System.setProperty("shipsmooth.jlink.dir", "/dev/null");
        System.setProperty("experimental.enabled", "false");
    }

    private void setWindowsProps() {
        System.setProperty("build.outputDir", tempDir.toString());
        System.setProperty("build.env", "prod");
        System.setProperty("build.platform", "windows");
        System.setProperty("plugin.base.name", "shipsmooth");
        System.setProperty("plugin.repo.name", "shipsmooth-windows");
        System.setProperty("plugin.skill.start.basename", "start");
        System.setProperty("plugin.version", "0.3.10");
        System.setProperty("plugin.description", "Agent coding workflow (Windows)");
        System.setProperty("skill.frontmatter", "");
        System.setProperty("shipsmooth.jlink.dir", "");
        System.setProperty("experimental.enabled", "false");
    }

    private void setDevProps() {
        System.setProperty("build.outputDir", tempDir.toString());
        System.setProperty("build.env", "dev");
        System.setProperty("build.platform", "claude");
        System.setProperty("plugin.base.name", "shipsmooth");
        System.setProperty("plugin.skill.start.basename", "start");
        System.setProperty("plugin.version", "0.2.0");
        System.setProperty("plugin.description", "Agent coding workflow (dev build)");
        System.setProperty("skill.frontmatter", "");
        System.setProperty("shipsmooth.jlink.dir", "/some/jlink/path");
        System.setProperty("experimental.enabled", "true");
    }
}