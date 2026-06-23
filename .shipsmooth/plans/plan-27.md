# Plan 27: Port Tasks Updater to Java (Native Image)

Port the TypeScript-based task management scripts (`init.ts`, `update-status.ts`, `add-comment.ts`, etc.) to Java. The Java implementation will be compiled to a native binary using GraalVM Native Image for Linux x86 64-bit.

## Context
The current task tracking system uses TypeScript scripts executed via Node.js. To improve performance (especially startup time) and provide a standalone binary, we will port this logic to Java and compile it to a native image.

## Design Decisions
- **Language:** Java 21.
- **CLI Framework:** [Picocli](https://picocli.info/) (excellent for Native Image).
- **XML Handling:** [Jakarta XML Binding (JAXB)](https://eclipse-ee4j.github.io/jaxb-ri/) using the existing `plan-tasks.xsd`.
- **Native Image:** GraalVM Native Image for a self-contained, fast-starting binary.
- **Module Structure:** A new Maven module `plugin-tasks-java`.

## Risk Analysis

- **Task 1: Project Setup & JAXB Generation [Low]**: Standard Maven setup and JAXB class generation from existing XSD. Hard technical dependency for all other tasks.
- **Task 2: Core Logic Port [Medium]**: Ensuring identical XML formatting and handling of extension points as the current TypeScript implementation. Dependency for CLI and Native Image.
- **Task 3: Picocli CLI Implementation [Low]**: Straightforward mapping of existing TS script arguments to Picocli commands. Dependency for testing the Native Image.
- **Task 4: Native Image Compilation & Config [High]**: Native Image often requires complex configuration for reflection and JAXB. This is the highest risk area.
- **Task 5: Integration & Verification [Medium]**: Ensuring the new binary is a drop-in replacement for the TS scripts.

## Tasks

### Task 1: Project Setup & JAXB Generation [Low]
- Create `plugin-tasks-java` module.
- Configure `pom.xml` with dependencies for Picocli, JAXB, and GraalVM plugin.
- Configure `jaxb2-maven-plugin` to generate Java classes from `plan-tasks.xsd`.

### Task 2: Core Logic Port [Medium]
- Implement `XmlService` to handle reading and writing the `plan-{N}-tasks.xml` file.
- Port logic from `init.ts`, `update-status.ts`, `add-comment.ts`, `add-deviation.ts`, `set-commit.ts`, and `project-update.ts`.
- Port logic from `show.ts` for displaying task status.

### Task 3: Picocli CLI Implementation [Low]
- Create a main entry point using Picocli.
- Implement subcommands matching the existing scripts: `init`, `update-status`, `add-comment`, `add-deviation`, `set-commit`, `project-update`, `show`.

### Task 4: Native Image Compilation & Config [High]
- Configure `native-image-maven-plugin`.
- Add necessary reflection configurations for JAXB (likely using `native-image-agent` or manual `reflect-config.json`).
- Verify the native binary builds and runs on Linux x86 64-bit.

### Task 5: Integration & Verification [Medium]
- Create a test suite that runs the Java binary against the same inputs as the TypeScript scripts.
- Compare the resulting XML files to ensure compatibility.
- Verify the binary integrates correctly with the `start` skill workflow.
