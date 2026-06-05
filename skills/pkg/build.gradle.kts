plugins {
    // Convention plugin from buildSrc: Semeru 25 toolchain, UTF-8, mavenLocal,
    // JUnit Jupiter. The Node/TS, JTE, and render wiring is added in later
    // plan-71 tasks (4–7); this file currently establishes the toolchain only.
    id("shipsmooth.java-conventions")
}
