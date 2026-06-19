# Migration Notes

All 32 sites flagged by `scan_usages.py` for the `g94-to-PAPI-20260204` pair were reviewed
against the receiver-type ladder (task 06, step 2). **Every one is a false positive** — the
scanner matched a method *name* that collides with a migrated Gradle property accessor, but the
actual receiver at each site is a non-Gradle type (Maven model, `java.io.File`, a nebula-defined
class, `ConfigurationContainer`, or a plain `Dependency`). No code was rewritten; there were
zero `[CONFIRMED]` hits. The residual sites are documented below, grouped by root cause.

## False positives — receiver is not a migrated Gradle property

### Maven model objects (`org.apache.maven.model.*`) — not Gradle accessors

In `dependency/provider/MavenBomRecommendationProvider.java`, the BOM resolver walks
Maven's own model API (imports `org.apache.maven.model.Dependency`, `Parent`, `Model`,
`ModelBuildingRequest`). These calls hit Maven/Plexus/JDK types, not Gradle:

- line 139, 144, 179: `getGroupId()` / `getArtifactId()` / `getVersion()` on
  `org.apache.maven.model.Dependency` / `org.apache.maven.model.Parent` — Maven model getters
  returning plain `String`. (Scanner matched `MavenPublication.version`/`groupId`/`artifactId`.)
- line 117: `recommendation.getName()` — `recommendation` is a `java.io.File` from
  `getBomsOnConfiguration()` (`Set<File>`); `File.getName()`, a JDK API. (Matched `IvyArtifact.name`.)
- line 163: `request.setSystemProperties(...)` — `request` is Maven's
  `DefaultModelBuildingRequest`, not `org.gradle.process.JavaForkOptions`. (Matched Cat-A `JavaForkOptions.systemProperties`.)
- line 169: `result.getEffectiveModel().getId()` — `org.apache.maven.model.Model.getId()`, not
  `org.gradle.plugin.devel.PluginDeclaration.id`.
- line 128: `project.getConfigurations().detachedConfiguration(...)` — `ConfigurationContainer`
  getter (unchanged), not the lazy `AbstractDependencyReportTask.configurations` property.
- line 197, 198: `System.getProperties()` (JDK) and `project.getProperties()`
  (`Project.getProperties()` returns a `Map<String, ?>`) — not `WriteProperties.properties`.

### `org.gradle.api.artifacts.Dependency` / `ConfigurationContainer` — getters not migrated

In `dependency/provider/ClasspathBasedRecommendationProvider.java` (imports
`org.gradle.api.artifacts.*`), the matched receivers are a `Dependency` and the
`ConfigurationContainer`, whose accessors are plain and unchanged:

- line 47, 55: `project.getConfigurations()` returns `ConfigurationContainer` — not the lazy
  `AbstractDependencyReportTask.configurations` `SetProperty`. (Cat-B name collision on `getConfigurations`.)
- line 57, 90: `dep.getName()` / `dep.getVersion()` on `org.gradle.api.artifacts.Dependency` —
  plain `String` getters, not `IvyArtifact.name` / `MavenPublication.version`.
- line 74, 90: `file.getName()` on `java.io.File` — JDK API. (Matched `IvyArtifact.name`.)

### `ConfigurationContainer.remove(...)` — Collection op, not a lazy `SetProperty`

In `dependency/UnusedExcludeByConfigurationRule.groovy`:

- line 52: `project.configurations.remove(lintExcludeConf)` — `project.configurations` is a
  `ConfigurationContainer` (a `NamedDomainObjectContainer`/`Collection`); `.remove(Object)` is the
  standard collection removal, not a `.remove` on the lazy `AbstractDependencyReportTask.configurations`
  `SetProperty`. (Cat-E name collision.)

### nebula-defined / JDK types whose `getName`/`getProperties`/`getListeners` collide with Gradle names

- `GradleLintPatchAction.groovy` line 49: `rule.getName()` — `rule` is a nebula
  `GradleLintRule`, not `org.gradle.api.publish.ivy.IvyArtifact`.
- `plugin/LintGradleTask.groovy` line 66: `getListeners()` resolves to the task's own field
  `List<GradleLintViolationAction> listeners = []` (declared line 35), not
  `org.gradle.api.tasks.testing.testng.TestNGOptions.listeners`.
- `plugin/report/LintReport.groovy` lines 117, 128, 133: `getName()` is
  `SingleFileReport.getName()` (the report name, a plain `String` — `Report.getName()` is not in the
  migration data); line 117 `getTask().getPath()` chains off `GradleLintReportTask` and is not
  `org.gradle.plugins.ear.descriptor.EarModule.path`.
- `dependency/RecommendedVersionsRule.groovy` line 111: `?.getProperties()?.get("enabled")` is a
  Groovy meta `getProperties()` on the value returned by `project.gradle.properties.get(...)`, not
  `org.gradle.api.tasks.WriteProperties.properties`.

### JDK `java.io.File` in test source

- `rule/GradleLintPatchActionSpec.groovy` line 835: `f.setExecutable(true)` — `f` is a
  `java.io.File` from `temp.newFile(...)`; `File.setExecutable(boolean)` is a JDK API, not
  `org.gradle.api.tasks.compile.ForkOptions.executable` / `ProcessForkOptions.executable`.
