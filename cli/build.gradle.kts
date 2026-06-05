plugins {
    id("shipsmooth.java-conventions")
    application
}

dependencies {
    implementation(project(":core"))
    implementation("info.picocli:picocli:4.7.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
}

application {
    // Launcher entrypoint — matches the jlink launcher
    // shipsmooth=io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth.
    mainClass.set("io.bitken.ss.cli.Shipsmooth")
}
