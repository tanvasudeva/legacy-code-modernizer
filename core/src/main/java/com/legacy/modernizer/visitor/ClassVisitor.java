package com.legacy.modernizer.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.legacy.modernizer.model.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts every ClassOrInterfaceDeclaration from a CompilationUnit,
 * including nested/inner classes.
 */
public class ClassVisitor extends VoidVisitorAdapter<List<ClassNode>> {

    private String currentPackage = "";

    public List<ClassNode> extract(CompilationUnit cu) {
        currentPackage = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString()).orElse("");
        List<ClassNode> nodes = new ArrayList<>();
        visit(cu, nodes);
        return nodes;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration n, List<ClassNode> nodes) {
        List<String> ifaces = n.getImplementedTypes().stream()
                .map(t -> t.getNameAsString()).collect(Collectors.toList());
        String superclass = n.getExtendedTypes().stream().findFirst()
                .map(t -> t.getNameAsString()).orElse(null);

        nodes.add(ClassNode.builder()
                .className(n.getNameAsString())
                .packageName(currentPackage)
                .fullyQualifiedName(buildFqn(n))
                .superclass(superclass)
                .interfaces(ifaces)
                .isInterface(n.isInterface())
                .isAbstract(n.isAbstract())
                .build());

        super.visit(n, nodes);
    }

    private String buildFqn(ClassOrInterfaceDeclaration n) {
        StringBuilder name = new StringBuilder(n.getNameAsString());
        Node parent = n.getParentNode().orElse(null);
        while (parent instanceof ClassOrInterfaceDeclaration outer) {
            name.insert(0, outer.getNameAsString() + ".");
            parent = outer.getParentNode().orElse(null);
        }
        return currentPackage.isEmpty() ? name.toString() : currentPackage + "." + name;
    }
}
