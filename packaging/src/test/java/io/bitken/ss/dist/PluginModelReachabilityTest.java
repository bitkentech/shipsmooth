package io.bitken.ss.dist;

import io.bitken.ss.resources.Os;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural guard (plan-79 Task 5): :packaging reaches io.bitken.ss.resources.Os
 * through the tiny :plugin-model leaf module — NOT through :skills:pkg or
 * :plugin-resources. PackageRuntime needs only the OS facts (launcher name,
 * Posix/Windows discriminant), so packaging must not depend on the skills-rendering
 * modules. A golden output diff can't catch packaging silently regaining a
 * :skills:pkg dependency; this test does. If this compiles and runs, the dependency
 * is wired correctly.
 */
class PluginModelReachabilityTest {

    @Test
    void os_resolvesFromPluginModel_forPackagingTargets() {
        assertEquals(Os.WINDOWS, Os.fromPackagingTarget("win32-x64"));
        assertEquals(Os.POSIX, Os.fromPackagingTarget("linux-x64"));
        assertTrue(Os.WINDOWS instanceof Os.Windows);
        assertTrue(Os.POSIX instanceof Os.Posix);
        // The launcher fact PackageRuntime actually consumes:
        assertEquals("shipsmooth.cmd", Os.WINDOWS.launcherFileName());
        assertEquals("shipsmooth", Os.POSIX.launcherFileName());
    }
}
