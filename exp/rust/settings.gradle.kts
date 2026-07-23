// Standalone, self-contained Gradle build for the experimental Rust port.
// Deliberately NOT included from the repo-root settings.gradle.kts: the main
// build must never see this project, so `./gradlew build` at the root cannot
// reach cargo.
//
// This file is what makes exp/rust a separate build root rather than an orphan
// — without it Gradle walks up to the root settings.gradle.kts and refuses to
// build a directory that build does not include.
//
// It ships its own wrapper (gradlew + gradle/wrapper/) so the directory stands
// alone and needs nothing from the repo root:
//
//   cd exp/rust && ./gradlew cargoBuild
//   cd exp/rust && ./gradlew cargoTest
//
// The wrapper is a copy of the root one; bump both together on a Gradle
// upgrade. Or skip Gradle entirely and use cargo directly from here.
rootProject.name = "ss-rust"
