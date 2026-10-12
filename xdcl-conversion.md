# Converting gradle-lint-plugin to Gradle XDCL

The build of this project was converted from Gradle's Groovy DSL to Gradle XDCL, and the lint plugin
gained an XDCL front end. The work is on the `xdcl-conversion` branch of the fork:
https://github.com/abstratt/gradle-lint-plugin/tree/xdcl-conversion. No pull request was opened.

## Test status

`./gradlew assemble` passes. `./gradlew build` fails: 26 of 494 tests fail (463 pass, 5 are skipped).

The suite was not run on the unconverted build, so the attribution below rests on the failure
messages rather than on a before/after comparison.

| Count | Cause |
|---|---|
| 21 | TestKit fixture scripts use multi-string dependency notation (`group:`, `name:`, `version:`), which Gradle 9.8 deprecates. nebula-test fails a build that prints deprecation warnings. |
| 2 | nebula-test tries to download the snapshot distribution by its version string and gets a 404 (`gradle-9.8.0-20261012070500+0000-bin.zip`). |
| 3 | Network timeouts while fetching Gradle distributions (9.1.0 and others). |

CI on the branch will fail on at least the first two groups.

Failing specs: `GradleLintPluginSpec` (11), `MultiProjectCircularDependencyRuleSpec` (7),
`MavenBomRecommendationProviderSpec` (2), and one each in `UnusedDependencyRuleSpec`,
`SpaceAssignmentRuleSpec`, `Issue39Spec`, `GradleLintRuleIntegSpec`, `FixGradleLintTaskSpec` and
`FindMethodReferencesSpec`.

## What changed

- **Build scripts.** `settings.gradle.xdcl` and `build.gradle.xdcl` replace the Groovy scripts.
  `settings.gradle` is now a comment-only marker and `build.gradle` is deleted.
- **`build-logic/`.** A new included build, itself declarative, that contributes the project types
  the main build configures and the Java reactions that do the imperative wiring:
  - `nebulaGradlePlugin` — the root project's type. Its script declares the description, contacts,
    plugin registrations, dependencies (with `reject` / `transitive` / `because` rules), forced
    versions, the Shadow includes and relocations, Java versions and release branch patterns.
    `NebulaGradlePluginReaction` applies Nebula plugin-plugin, Kotlin, Shadow and
    `java-gradle-plugin`, and keeps the conventions that are not data: Groovy-before-Kotlin compile
    ordering, removing `gradleApi()` from `implementation`, `jar` disabled in favour of
    `shadowJar`, the test summary box, `maxParallelForks`, the four `dependencyReport*` tasks.
  - `xdclPluginModule` — the type of the new XDCL module (see below).
  - `buildScan` — a settings block that applies and configures Develocity.
- **Wrapper.** Re-pinned from 9.0.0 to `9.8.0-branch-xdclRelease-20261012070500+0000`, with its
  checksum.
- **`gradle-lint-xdcl-plugin/`.** A new Java 17 module with the settings plugin
  `com.netflix.nebula.lint.xdcl`. A `gradleLint { … }` block in `settings.gradle.xdcl` applies the
  lint plugin to every project and configures it (`rules`, `criticalRules`, `excludedRules`,
  `reportFormat`, `reportOnlyFixableViolations`, `alwaysRun`, `autoLintAfterFailure`,
  `skipForTasks`).
- **Core plugin.** `.xdcl` build files are skipped instead of being parsed as Groovy
  (`SourceCollector.isLintable`, used by `SourceCollector` and `LintService`), with a new case in
  `SourceCollectorTest`.
- **`xdcl-example/`.** A consumer build that lints a Groovy subproject from XDCL settings.
- **README.** A "Using from an XDCL build" section, and XDCL build scripts added to the list of
  what lint does not support.

Left alone: `example/` and `issues/issue38/` (standalone consumer samples), `gradle.properties`,
`gradle.lockfile`, and the CI workflows.

## What was verified

- The four dependency reports (`build/reports/project/*-dependencies.txt`), the shaded jar's entry
  list and the generated POM match the pre-conversion build, and `gradle.lockfile` is untouched.
  The POM differs only in build date and branch name.
- Every `.xdsl` and `.gradle.xdcl` in the main build validates clean with the XDCL language server.
- `xdcl-example` reports the two expected violations from `lintGradle` (`dependency-parentheses`
  and `dependency-tuple`) and honours the `gradleLint.ignore` block.
- Build scans still publish. Six were published to gradle.com during this work, since the build
  agrees to the terms of use itself.

## Things that differ from the plan

- **Kotlin flag.** Kotlin 2.2.0 cannot read the Kotlin 2.4 metadata in the 9.8 Gradle API, so
  `compileKotlin` now passes `-Xskip-metadata-version-check`. Bumping Kotlin instead would have
  changed the lockfile and the published `kotlin-stdlib` version.
- **Own project type for the XDCL module.** It uses `xdclPluginModule` from `build-logic` rather
  than the built-in `xdclGradlePlugin`, because the built-in type only accepts coordinate
  dependencies. It depends on the root project's `shadowRuntimeElements` variant, which carries
  the shaded jar together with the dependencies the plugin leaves external.
- **Package name.** The build-logic package and plugin id are
  `com.netflix.nebula.lint.buildlogic`, because a `build` path segment is caught by the `build/`
  rule in `.gitignore`.

## Known limits

- The XDCL language server cannot validate `xdcl-example/settings.gradle.xdcl`: the example
  includes its parent directory as a build, and the sync tool drops builds outside the tree.
  Gradle itself runs it fine.
- `gradle-lint-xdcl-plugin` has no publishing setup and no automated test; the example build is
  its only check.
- Lint rules still only understand Groovy scripts, so on a project configured purely by XDCL there
  is nothing to lint yet. `ignore` / `fixme` blocks are Groovy-script markers and have no XDCL
  equivalent.

## Working with the converted build

- Sync the schema context from the repository root (it covers `build-logic/` too):
  `xdcl-gradle-sync sync .` Syncing `build-logic/` on its own does not work — it has no wrapper, so
  the sync falls back to a stock Gradle that knows nothing about XDCL.
- Validate scripts without running Gradle:
  `xdcl-lsp-client validate --root "$PWD" settings.gradle.xdcl build.gradle.xdcl`
- Try the XDCL front end: `cd xdcl-example && ./gradlew lintGradle` (it fails by design, reporting
  the two violations).
