package com.netflix.nebula.lint.xdcl;

import com.netflix.nebula.lint.plugin.GradleLintExtension;
import com.netflix.nebula.lint.plugin.GradleLintPlugin;
import org.gradle.api.initialization.Settings;
import org.gradle.api.xdcl.Reaction;
import org.gradle.api.xdcl.ReactionScope;

import java.util.ArrayList;
import java.util.List;

public class GradleLintReaction implements Reaction<GradleLint, Settings> {
    @Override
    public void on(GradleLint data, Settings settings, ReactionScope scope) {
        settings.getGradle().allprojects(project -> {
            // the lint plugin refuses projects with a kotlin build script
            if (project.getBuildFile().getName().toLowerCase().endsWith(".kts")) {
                return;
            }
            project.getPluginManager().apply(GradleLintPlugin.class);

            GradleLintExtension lint = project.getExtensions().getByType(GradleLintExtension.class);
            lint.setRules(new ArrayList<>(data.rules().getOrElse(List.of())));
            lint.setCriticalRules(new ArrayList<>(data.criticalRules().getOrElse(List.of())));
            lint.setExcludedRules(new ArrayList<>(data.excludedRules().getOrElse(List.of())));
            lint.setReportFormat(data.reportFormat().get().toString());
            lint.setReportOnlyFixableViolations(data.reportOnlyFixableViolations().get());
            lint.setAlwaysRun(data.alwaysRun().get());
            lint.setAutoLintAfterFailure(data.autoLintAfterFailure().get());
            data.skipForTasks().getOrElse(List.of()).forEach(lint::skipForTask);
        });
    }
}
