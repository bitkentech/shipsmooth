# Maven→Gradle Final Parity Sign-off (plan-72, Task 1)

> **Status:** PASS. Final full-payload parity check, Gradle vs Maven, performed on
> `t/72-maven-teardown` while the `pom.xml` files still exist — the last point at which
> Maven can be diffed against before Task 2 deletes it. This is the hard gate before
> Maven removal.

## Method

For each of the five plugin payloads, a clean Maven baseline was built offline and diffed
against the Gradle assembly into a separate directory:

```
rm -rf build*                         # mvn compile is incremental — stale dirs poison the baseline
mvn -o compile -P<profile>            # baseline into the profile's default build*/ dir
./gradlew assembleX -Pbuild.outputDir=<dir>
diff  (file-set)  +  diff (content, per file)
```

Harness: `.agents/tmp/parity-72.sh <variant>` (scratch, not committed).

### Known-noise, normalized out

Two differences are expected and **not** build-logic divergence:

1. **Version stamp `0.3.14` → `0.3.15`.** The `pom.xml` files are pinned at `0.3.14`;
   `gradle.properties` is `0.3.15` (the release bump updated Gradle's source, not the poms,
   since Gradle is now the release path). Every payload therefore shows `0.3.14`↔`0.3.15`
   string diffs. Normalized (`s/0\.3\.1[45]/X.Y.Z/g`) in the comparison. **This noise
   disappears entirely once Task 2 deletes the poms** — Gradle becomes the sole version
   source.
2. **`dist/session-start-config.json` jq stamp.** Legacy Maven post-build `jq` version stamp;
   Gradle expands `plugin.version` into the manifest at build time, making it a no-op
   (established in plan-71 Task 26). Excluded from the content diff, as in the plan-71 parity
   scripts.

## Result — all five payloads PARITY OK

| Payload | Maven profile | Gradle task | File-set | Content (normalized) |
|---|---|---|---|---|
| claude-dev | `dev` | `assembleClaudeDev` | identical | identical |
| gemini-dev | `gemini-dev` | `assembleGeminiDev` | identical | identical |
| claude-prod | `prod` | `assembleClaudeProd` | identical | identical |
| gemini-prod | `gemini` | `assembleGeminiProd` | identical | identical |
| windows | `windows` | `assembleWindows` | identical | identical |

Every payload: file-set identical, content byte-identical modulo the two known-noise items above.

## Runtime zip (`runtime-<ver>/` linux-x64)

Signed off on combined evidence rather than a fresh Maven jlink rebuild (which would re-prove
already-established parity at the heaviest build cost, with poms pinned at 0.3.14):

- **Identical assembly code.** Maven (`exec-maven-plugin`) and Gradle (`JavaExec`) both invoke
  the *same* `io.bitken.ss.dist.PackageRuntime` to stage and zip the runtime. The zip logic is
  one code path, not two.
- **jlink image byte-parity** was established in plan-71 Task 14 (Gradle 5-platform jlink images
  + OpenJ9 SCC launcher verified against the Maven jlink profile).
- **Structural zip diff:** the last Maven-era zip (`shipsmooth-0.3.14-linux-x64.zip`) vs a fresh
  Gradle `packageRuntime_linux-x64` zip (`shipsmooth-0.3.15-linux-x64.zip`) — file-set identical
  (`zipinfo -1`), and **0 entries differ** in content (version-normalized). Sizes match exactly
  (54,196,092 bytes).

## Conclusion

Gradle produces byte-identical output to Maven for all five payloads and the linux-x64 runtime
zip, modulo the version stamp (a pom-vs-gradle source difference that ends at Task 2) and the
legacy jq stamp (already a Gradle no-op). **The hard gate before Maven removal is cleared.**
