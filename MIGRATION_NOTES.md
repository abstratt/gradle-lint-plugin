# Migration Notes

Residual scanner hits after task 06, all reviewed against the receiver-type ladder
(MIGRATION_RULES.md / task 06 step 2). The scanner reported **0 Cat-A, 0 Cat-C, 0 Cat-D
confirmed** sites and **no `[CONFIRMED]` hits at all**; every remaining hit is `[unconfirmed]`
and, on inspection, a **false positive** — the matched method *name* (`getName`, `getVersion`,
`getGroupId`, `getProperties`, `getConfigurations`, `remove`, …) belongs to a non-Gradle type
or to a Gradle API that is not in `migration-data.json`. No source rewrites were required for
this migration; the entries below are the false-positive audit trail.

## Cat-B false positives — receivers are not migrated Gradle property types

### `src/main/groovy/com/netflix/nebula/lint/GradleLintPatchAction.groovy`
- line 49: `rule.getName()` — `rule` is a project `GradleLintRule` (from `violations.*.rule`),
  not `org.gradle.api.publish.ivy.IvyArtifact`. `GradleLintRule.getName()` is a plain `String`
  getter, unchanged.

### `src/main/groovy/com/netflix/nebula/lint/plugin/LintGradleTask.groovy`
- line 66: `getListeners()` resolves to this class's own field
  `List<GradleLintViolationAction> listeners` (declared line 35), not
  `org.gradle.api.tasks.testing.testng.TestNGOptions.listeners`.

### `src/main/groovy/com/netflix/nebula/lint/plugin/report/LintReport.groovy`
- line 117 `getName()` — own method on `LintReport`, not `IvyArtifact.name`.
- line 117 `getTask().getPath()` — `getTask()` returns `GradleLintReportTask`; `getPath()` is
  `org.gradle.api.Task.getPath()` (returns `String`), a Task-only accessor not lazy-migrated in
  Gradle 10, not `org.gradle.plugins.ear.descriptor.EarModule.path`.
- line 128, line 133: `getName()` — own `LintReport` method, not `IvyArtifact.name`.

### `src/main/groovy/com/netflix/nebula/lint/rule/dependency/RecommendedVersionsRule.groovy`
- line 111: `?.getProperties()` is called on the result of
  `project.gradle.properties.get("experimentalFeatures")` (a generic Groovy/`Object` map value),
  not on `org.gradle.api.tasks.WriteProperties`. No Gradle property type at the receiver.

### `src/main/groovy/com/netflix/nebula/lint/rule/dependency/provider/ClasspathBasedRecommendationProvider.java`
- line 47, line 55: `project.getConfigurations()` returns
  `org.gradle.api.artifacts.ConfigurationContainer` (the container), not the
  `AbstractDependencyReportTask.configurations` task property. `Project.getConfigurations()` is
  unchanged in Gradle 10.
- line 57, line 90: `dep.getName()` / `dep.getVersion()` where `dep` is
  `org.gradle.api.artifacts.Dependency`. `Dependency.getName()`/`getVersion()` are not in
  `migration-data.json` (the matched entries are `IvyArtifact.name` / `MavenPublication.version`).
- line 74: `file.getName()` where `file` is `java.io.File` — JDK API.

### `src/main/groovy/com/netflix/nebula/lint/rule/dependency/provider/MavenBomRecommendationProvider.java`
- line 117: `recommendation.getName()` — `recommendation` is `java.io.File` (JDK), not
  `IvyArtifact`.
- line 128: `project.getConfigurations()` returns `ConfigurationContainer`, not the task
  property; unchanged in Gradle 10.
- line 139, line 144: `dependency`/`parent` are `org.apache.maven.model.Dependency` /
  `org.apache.maven.model.Parent` (Apache Maven model). `getGroupId()`/`getArtifactId()`/
  `getVersion()` are Maven APIs, not `org.gradle.api.publish.maven.MavenPublication`.
- line 163: `System.getProperties()` — JDK `java.lang.System`, not `WriteProperties.properties`.
- line 169: `result.getEffectiveModel().getId()` — `getEffectiveModel()` returns
  `org.apache.maven.model.Model`; `Model.getId()` is Apache Maven, not
  `org.gradle.plugin.devel.PluginDeclaration.id`.
- line 179: `d` is `org.apache.maven.model.Dependency`; `getGroupId()`/`getArtifactId()`/
  `getVersion()` are Apache Maven APIs, not `MavenPublication`.
- line 197: `System.getProperties()` — JDK API.
- line 198: `project.getProperties()` is `org.gradle.api.Project.getProperties()` (returns
  `Map<String, ?>`), a Project method unchanged in Gradle 10, not
  `org.gradle.api.tasks.WriteProperties.properties`.

## Cat-E false positive

### `src/main/groovy/com/netflix/nebula/lint/rule/dependency/UnusedExcludeByConfigurationRule.groovy`
- line 52: `project.configurations.remove(lintExcludeConf)` — `project.configurations` is
  `org.gradle.api.artifacts.ConfigurationContainer`; `.remove(...)` is the standard
  `DomainObjectCollection`/`NamedDomainObjectCollection` `remove` (returns `boolean`), which
  still exists in Gradle 10. The flagged `AbstractDependencyReportTask.configurations` is an
  unrelated task property of type `Set`.
