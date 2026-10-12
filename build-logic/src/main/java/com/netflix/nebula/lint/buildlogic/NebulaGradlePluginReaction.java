package com.netflix.nebula.lint.buildlogic;

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.GroovySourceDirectorySet;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.compile.GroovyCompile;
import org.gradle.api.tasks.diagnostics.DependencyReportTask;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.api.tasks.testing.logging.TestLogEvent;
import org.gradle.api.xdcl.Reaction;
import org.gradle.api.xdcl.ReactionScope;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension;
import org.gradle.plugin.devel.tasks.ValidatePlugins;
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class NebulaGradlePluginReaction implements Reaction<NebulaGradlePlugin, Project> {

    private static final List<String> CLASSPATHS =
            List.of("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath");

    @Override
    public void on(NebulaGradlePlugin data, Project project, ReactionScope scope) {
        project.getPluginManager().apply("com.netflix.nebula.plugin-plugin");
        project.getPluginManager().apply("org.jetbrains.kotlin.jvm");
        project.getPluginManager().apply("com.gradleup.shadow");
        project.getPluginManager().apply("java-gradle-plugin");

        project.setDescription(data.description().get());
        configureContacts(data, project);
        Configuration bundled = configureConfigurations(project);
        configureDependencies(data, project);
        configurePlugins(data, project);
        configureShadowJar(data, project, bundled);
        configureCompilation(data, project);
        configureTests(data, project);
        configureDependencyReports(project);
        configureNebulaConventions(data, project);
    }

    private static void configureContacts(NebulaGradlePlugin data, Project project) {
        Object contacts = project.getExtensions().getByName("contacts");
        data.contacts().getOrElse(Map.of()).forEach((email, contact) -> {
            Object person = InvokerHelper.invokeMethod(contacts, "addPerson", email);
            InvokerHelper.setProperty(person, "moniker", contact.moniker().get());
            if (contact.github().isPresent()) {
                InvokerHelper.setProperty(person, "github", contact.github().get());
            }
        });
    }

    private static Configuration configureConfigurations(Project project) {
        ConfigurationContainer configurations = project.getConfigurations();
        // Named `plugin` for continuity with the dependency lock state and reports.
        Configuration bundled = configurations.create("plugin", c -> c.setDescription(
                "The compile dependencies for the plugin, excluding the local dependencies to avoid problems with Shadow"));
        Configuration shadow = configurations.getByName("shadow");
        for (String classpath : CLASSPATHS) {
            configurations.getByName(classpath).extendsFrom(shadow, bundled);
        }
        return bundled;
    }

    private static void configureDependencies(NebulaGradlePlugin data, Project project) {
        if (!data.dependencies().isPresent()) {
            return;
        }
        PluginDependencies declared = data.dependencies().get();
        DependencyHandler dependencies = project.getDependencies();

        List<String> forced = declared.force().getOrElse(List.of());
        if (!forced.isEmpty()) {
            project.getConfigurations().configureEach(c -> c.getResolutionStrategy().force(forced.toArray()));
        }

        addAll(dependencies, "shadow", declared.shadow());
        addAll(dependencies, "plugin", declared.bundled());
        // compileOnly carries the AbstractRuleSpec test harness's dependencies: it lives in src/main.
        addAll(dependencies, "compileOnly", declared.compileOnly());
        addAll(dependencies, "testImplementation", declared.testImplementation());
        if (declared.compileOnlyGradleTestKit().get()) {
            dependencies.add("compileOnly", dependencies.gradleTestKit());
        }

        project.afterEvaluate(p -> p.getConfigurations().named("implementation",
                c -> c.getDependencies().remove(p.getDependencies().gradleApi())));
    }

    private static void addAll(DependencyHandler dependencies, String configuration, Provider<List<DependencyNotation>> notations) {
        for (DependencyNotation notation : notations.getOrElse(List.of())) {
            if (notation instanceof DependencyNotation.StringValue coordinates) {
                dependencies.add(configuration, coordinates.value());
            } else if (notation instanceof Module module) {
                ExternalModuleDependency dependency = (ExternalModuleDependency) dependencies.create(module.notation().get());
                dependency.setTransitive(module.transitive().get());
                List<String> rejected = module.reject().getOrElse(List.of());
                if (!rejected.isEmpty()) {
                    dependency.version(version -> version.reject(rejected.toArray(new String[0])));
                }
                if (module.because().isPresent()) {
                    dependency.because(module.because().get());
                }
                dependencies.add(configuration, dependency);
            }
        }
    }

    private static void configurePlugins(NebulaGradlePlugin data, Project project) {
        GradlePluginDevelopmentExtension gradlePlugin = project.getExtensions().getByType(GradlePluginDevelopmentExtension.class);
        data.plugins().get().forEach((name, declared) -> gradlePlugin.getPlugins().create(name, plugin -> {
            plugin.setId(declared.id().get());
            plugin.setDisplayName(declared.displayName().get());
            plugin.setDescription(declared.description().get());
            plugin.setImplementationClass(declared.implementationClass().get());
            plugin.getTags().set(declared.tags().getOrElse(List.of()));
        }));
    }

    // Relocate dependencies not available in Maven Central
    // Replaces the main artifact by removing the classifier for the shadow jar, and replacing jar with shadowJar
    // Relocated dependencies are removed from the generated pom
    private static void configureShadowJar(NebulaGradlePlugin data, Project project, Configuration bundled) {
        TaskContainer tasks = project.getTasks();
        tasks.named("shadowJar", ShadowJar.class, shadowJar -> {
            shadowJar.getConfigurations().set(Set.of(bundled));
            shadowJar.getArchiveClassifier().set((String) null);
            if (data.shading().isPresent()) {
                Shading shading = data.shading().get();
                shadowJar.dependencies(filter -> {
                    for (String included : shading.include().getOrElse(List.of())) {
                        filter.include(filter.dependency(included));
                    }
                });
                shading.relocate().getOrElse(Map.of()).forEach(shadowJar::relocate);
            }
        });
        tasks.named("jar", jar -> {
            jar.setEnabled(false);
            jar.dependsOn(tasks.named("shadowJar"));
        });
    }

    private static void configureCompilation(NebulaGradlePlugin data, Project project) {
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(JavaLanguageVersion.of(data.javaVersion().get()));
        SourceSet main = java.getSourceSets().getByName("main");

        // Kotlin compiles first, stubbing Java dependencies, however it can't stub Groovy and we need to call Groovy code from Kotlin. For details see:
        // https://discuss.gradle.org/t/kotlin-groovy-and-java-compilation/14903/10
        TaskContainer tasks = project.getTasks();
        tasks.named("compileGroovy", GroovyCompile.class, compileGroovy ->
                compileGroovy.setClasspath(main.getCompileClasspath()));
        tasks.named("compileKotlin", KotlinCompile.class, compileKotlin -> {
            // Kotlin also depends on the result of Groovy compilation
            // which automatically makes it depend of compileGroovy
            compileKotlin.getLibraries().from(
                    main.getExtensions().getByType(GroovySourceDirectorySet.class).getClassesDirectory());
            // The Gradle API on the compile classpath carries metadata of a newer Kotlin than this compiler
            compileKotlin.getCompilerOptions().getFreeCompilerArgs().add("-Xskip-metadata-version-check");
        });
        tasks.named("compileTestGroovy", GroovyCompile.class, compileTestGroovy ->
                compileTestGroovy.getJavaLauncher().set(testLauncher(data, project)));
    }

    private static Provider<JavaLauncher> testLauncher(NebulaGradlePlugin data, Project project) {
        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        return toolchains.launcherFor(spec ->
                spec.getLanguageVersion().set(JavaLanguageVersion.of(data.testJavaVersion().get())));
    }

    private static void configureTests(NebulaGradlePlugin data, Project project) {
        TaskContainer tasks = project.getTasks();
        tasks.withType(Test.class).configureEach(test -> {
            test.setMaxHeapSize(data.testMaxHeapSize().get());
            test.setMaxParallelForks(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            test.getTestLogging().events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED);
            test.addTestListener(new SuiteSummary());
            test.getJavaLauncher().set(testLauncher(data, project));
        });
        tasks.named("test", test -> test.dependsOn("shadowJar", "generatePomFileForNebulaPublication"));
    }

    private static void configureDependencyReports(Project project) {
        TaskContainer tasks = project.getTasks();
        for (String classpath : CLASSPATHS) {
            String name = "dependencyReport" + Character.toUpperCase(classpath.charAt(0)) + classpath.substring(1);
            tasks.register(name, DependencyReportTask.class, report -> {
                report.setConfigurations(Set.of(project.getConfigurations().getByName(classpath)));
                report.setOutputFile(project.file("build/reports/project/" + classpath + "-dependencies.txt"));
            });
            tasks.named("check", check -> check.dependsOn(name));
            tasks.named("assemble", assemble -> assemble.dependsOn(name));
        }
    }

    private static void configureNebulaConventions(NebulaGradlePlugin data, Project project) {
        Object nebulaRelease = project.getExtensions().findByName("nebulaRelease");
        if (nebulaRelease != null) {
            for (String pattern : data.releaseBranchPatterns().getOrElse(List.of())) {
                InvokerHelper.invokeMethod(nebulaRelease, "addReleaseBranchPattern", pattern);
            }
        }
        project.getTasks().named("validatePlugins", ValidatePlugins.class, validatePlugins ->
                validatePlugins.getEnableStricterValidation().set(false));
        InvokerHelper.setProperty(project.getExtensions().getByName("javaCrossCompile"), "disableKotlinSupport", true);
    }

    /** Prints a one-line summary box once the outermost test suite finishes. */
    private static final class SuiteSummary implements TestListener {
        @Override
        public void beforeSuite(TestDescriptor suite) {
        }

        @Override
        public void afterSuite(TestDescriptor suite, TestResult result) {
            if (suite.getParent() != null) {
                return;
            }
            String output = "Results: " + result.getResultType() + " (" + result.getTestCount() + " tests, "
                    + result.getSuccessfulTestCount() + " passed, " + result.getFailedTestCount() + " failed, "
                    + result.getSkippedTestCount() + " skipped)";
            String rule = "-".repeat(output.length() + 6);
            System.out.println("\n" + rule + "\n|  " + output + "  |\n" + rule);
        }

        @Override
        public void beforeTest(TestDescriptor testDescriptor) {
        }

        @Override
        public void afterTest(TestDescriptor testDescriptor, TestResult result) {
        }
    }
}
