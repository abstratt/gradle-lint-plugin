package com.netflix.nebula.lint.plugin

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codenarc.source.SourceCode
import org.codenarc.source.SourceString
import org.gradle.api.Project

class SourceCollector {

    /**
     * Lint rules inspect Groovy build scripts: an XDCL build file has nothing for them to parse.
     */
    static boolean isLintable(File buildFile) {
        buildFile.exists() && !buildFile.name.toLowerCase().endsWith('.xdcl')
    }

    /**
     * It scans given build file for possible `apply from: 'another.gradle'` and recursively
     * collect all build files which are present.
     */
    static List<File> getAllFiles(File buildFile, Project project) {
        if (isLintable(buildFile)) {
            List<File> result = new ArrayList<>()
            result.add(buildFile)
            SourceCode sourceCode = new SourceString(buildFile.text)
            ModuleNode ast = sourceCode.getAst()
            if (ast != null && ast.getClasses() != null) {
                for (ClassNode classNode : ast.getClasses()) {
                    AppliedFilesAstVisitor visitor = new AppliedFilesAstVisitor(project)
                    visitor.visitClass(classNode)
                    result.addAll(visitor.appliedFiles)
                }
            }
            return result
        } else {
            return Collections.emptyList()
        }
    }
}
