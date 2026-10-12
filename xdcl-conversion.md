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

## Is the new build definition better than the old one?

In short: the files a maintainer edits day to day are better; the build as a whole is larger and,
for a single-project repository, not clearly a net win yet.

The comparison in numbers:

| | Before | After |
|---|---|---|
| Build scripts | `settings.gradle` + `build.gradle`: 239 lines | `settings.gradle.xdcl` + `build.gradle.xdcl`: 132 lines |
| Supporting build logic | none | `build-logic/`: about 440 lines (110 schema, 300 Java, 34 scripts and descriptor) |
| Total | 239 lines, 2 files | about 570 lines, 11 files |

### Where the new definition is better

- **The build script is data only.** `build.gradle.xdcl` holds the things that actually change —
  dependencies, plugin registrations, relocations, Java versions — with no task wiring, closures or
  `afterEvaluate` between them. The old script mixed both, so the dependency list sat next to a
  test-listener closure and compile-ordering hacks.
- **It is checked against a schema before Gradle runs.** A misspelled property, a wrong type or a
  missing required value is reported by the language server in milliseconds, with a location. In
  the Groovy script the same mistakes surface when the build configures, or not at all: a
  misspelled property on a dynamic object can be silently ignored.
- **The model is documented and discoverable.** Every property in `nebula-build.xdsl` has a type
  and most have a doc comment, which editors show on hover and completion. The old script's
  vocabulary was whatever the applied plugins happened to add at runtime.
- **Tooling can read and rewrite it.** Because the script is a value tree rather than a program, a
  tool can answer "what is the effective value of X and where does it come from" or edit a
  dependency without executing or parsing arbitrary Groovy.
- **Intent has names.** The configuration the old build called `plugin` — easily confused with
  Gradle plugins — is `bundled` in the script, with documentation saying it is packed into the
  jar and kept out of the POM. `shading { include, relocate }` states as a pair what was spread
  over a `shadowJar` block.
- **Build logic is typed, compiled code.** The reaction is Java checked by the compiler, where the
  old logic was untyped Groovy evaluated on each configuration.
- **Conventions are reusable.** A second Nebula-style plugin project in this build could declare
  `nebulaGradlePlugin { … }` and get the same wiring. With one such project today this is a
  potential benefit, not a realised one.

### Where it is worse, or no better

- **There is more of it.** Total build definition more than doubled, and what was one readable
  file is now a script, a schema and a reaction. Understanding what the build *does* means reading
  `NebulaGradlePluginReaction.java`; understanding what it *is* means reading `build.gradle.xdcl`.
- **The imperative code did not go away; it moved.** Everything that was awkward in `build.gradle`
  (compile ordering, removing `gradleApi()`, the test summary) is still there, now in Java and
  somewhat more verbose.
- **New concepts need three edits.** Adding a dependency or a relocation is a one-line change, as
  before. Adding something the schema has no slot for — a new configuration, say — means changing
  the schema, the reaction and the script.
- **The schema is bespoke.** `nebulaGradlePlugin` was shaped to fit this one build. It is not a
  shared, maintained project type, so nobody else's documentation or experience applies to it.
- **Some typing was lost at the edges.** The Nebula extensions (`contacts`, `nebulaRelease`,
  `javaCrossCompile`) are configured through Groovy's dynamic invoker by name, so a rename
  upstream fails at configuration time rather than at compile time. Plugin implementation classes
  are plain strings in the script, unchecked, exactly as before.
- **It depends on pre-release tooling.** The build now needs a snapshot Gradle distribution that
  can change without notice, which is also what the 26 test failures trace back to. The old build
  ran on a released Gradle.
- **Editor and ecosystem support is thin.** XDCL needs the toolkit or the IntelliJ plugin; IDEs,
  Dependabot-style updaters and most contributors know `build.gradle` and do not know
  `.gradle.xdcl`. The `latest.release` and `9.+` dynamic versions were carried over unchanged, so
  nothing improved there either.

### What stayed the same

The outputs. Resolved dependencies, the shaded jar's contents, the POM and the lockfile are
identical, so the conversion changed how the build is *described*, not what it *produces*.

### Verdict

The declarative script is a real improvement in readability, validation and tool-friendliness, and
that is the part people touch most often. The cost is a larger, more layered build and a dependency
on experimental tooling. That trade pays off when several projects share the conventions or when
tooling needs to read and edit build definitions; for this repository on its own, today, it is
closer to break-even, with the snapshot distribution as the main drawback.

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
