package com.legacy.modernizer.extractor;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.legacy.modernizer.model.ClassNode;
import com.legacy.modernizer.model.DependencyGraph;
import com.legacy.modernizer.visitor.CallGraphVisitor;
import com.legacy.modernizer.visitor.ClassVisitor;
import com.legacy.modernizer.visitor.ImportVisitor;
import com.legacy.modernizer.visitor.MethodVisitor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Orchestrates all AST visitors over a source directory tree,
 * producing a DependencyGraph with fully-populated class nodes and call edges.
 */
public class DependencyExtractor {

    private final ClassVisitor     classVisitor     = new ClassVisitor();
    private final MethodVisitor    methodVisitor    = new MethodVisitor();
    private final ImportVisitor    importVisitor    = new ImportVisitor();
    private final CallGraphVisitor callGraphVisitor = new CallGraphVisitor();

    public DependencyGraph extract(Path sourceDir) {
        DependencyGraph graph = new DependencyGraph(sourceDir.toAbsolutePath().toString());

        try (Stream<Path> files = Files.walk(sourceDir)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                 .forEach(file -> processFile(file, graph));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk source directory: " + sourceDir, e);
        }
        return graph;
    }

    private void processFile(Path file, DependencyGraph graph) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(file);
        } catch (ParseProblemException | IOException e) {
            System.err.println("Skipping " + file.getFileName() + ": " + e.getMessage());
            return;
        }

        List<String> imports = importVisitor.extract(cu);
        List<ClassNode> classNodes = classVisitor.extract(cu);
        List<ClassOrInterfaceDeclaration> declarations = cu.findAll(ClassOrInterfaceDeclaration.class);

        for (int i = 0; i < classNodes.size() && i < declarations.size(); i++) {
            ClassNode node = classNodes.get(i);
            ClassOrInterfaceDeclaration decl = declarations.get(i);

            node.setImports(imports);
            node.setMethods(methodVisitor.extract(decl));

            graph.addClass(node);
            callGraphVisitor.extract(decl, node.getFullyQualifiedName()).forEach(graph::addEdge);
        }
    }
}
