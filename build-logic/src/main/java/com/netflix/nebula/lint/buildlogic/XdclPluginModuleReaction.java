package com.netflix.nebula.lint.buildlogic;

import org.gradle.api.Project;
import org.gradle.api.xdcl.Reaction;
import org.gradle.api.xdcl.ReactionScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class XdclPluginModuleReaction implements Reaction<XdclPluginModule, Project> {
    @Override
    public void on(XdclPluginModule data, Project project, ReactionScope scope) {
        project.getPluginManager().apply("java-library");
        project.getPluginManager().apply("java-gradle-plugin");
        project.getPluginManager().apply("xdcl-gradle-plugin");

        List<ProjectDependency> declared = data.projectDependencies()
                .flatMap(ProjectDependencies::implementation)
                .getOrElse(List.of());
        for (ProjectDependency dependency : declared) {
            Map<String, String> notation = new LinkedHashMap<>();
            notation.put("path", dependency.path().get());
            if (dependency.configuration().isPresent()) {
                notation.put("configuration", dependency.configuration().get());
            }
            project.getDependencies().add("implementation", project.getDependencies().project(notation));
        }
    }
}
