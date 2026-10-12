package com.netflix.nebula.lint.buildlogic;

import com.gradle.develocity.agent.gradle.DevelocityConfiguration;
import org.gradle.api.initialization.Settings;
import org.gradle.api.xdcl.Reaction;
import org.gradle.api.xdcl.ReactionScope;

public class BuildScanReaction implements Reaction<BuildScan, Settings> {
    @Override
    public void on(BuildScan data, Settings settings, ReactionScope scope) {
        settings.getPluginManager().apply("com.gradle.develocity");
        settings.getExtensions().configure(DevelocityConfiguration.class, develocity -> {
            develocity.getBuildScan().getTermsOfUseUrl().set(data.termsOfUseUrl());
            develocity.getBuildScan().getTermsOfUseAgree().set(data.termsOfUseAgree());
        });
    }
}
