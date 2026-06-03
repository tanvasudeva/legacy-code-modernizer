package com.legacy.modernizer.visitor;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.legacy.modernizer.model.MethodInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts the direct methods of a ClassOrInterfaceDeclaration.
 */
public class MethodVisitor extends VoidVisitorAdapter<List<MethodInfo>> {

    public List<MethodInfo> extract(ClassOrInterfaceDeclaration classDecl) {
        List<MethodInfo> methods = new ArrayList<>();
        for (MethodDeclaration m : classDecl.getMethods()) {
            visit(m, methods);
        }
        return methods;
    }

    @Override
    public void visit(MethodDeclaration n, List<MethodInfo> methods) {
        List<String> paramTypes = n.getParameters().stream()
                .map(p -> p.getTypeAsString())
                .collect(Collectors.toList());
        methods.add(MethodInfo.builder()
                .name(n.getNameAsString())
                .returnType(n.getTypeAsString())
                .parameterTypes(paramTypes)
                .build());
        super.visit(n, methods);
    }
}
