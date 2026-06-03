package com.legacy.modernizer.visitor;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.legacy.modernizer.model.CallEdge;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects method-call relationships within a single class.
 */
public class CallGraphVisitor extends VoidVisitorAdapter<List<CallEdge>> {

    private String currentClassName  = "";
    private String currentMethodName = "<clinit>";

    public List<CallEdge> extract(ClassOrInterfaceDeclaration classDecl, String fqn) {
        this.currentClassName = fqn;
        List<CallEdge> edges = new ArrayList<>();
        visit(classDecl, edges);
        return edges;
    }

    @Override
    public void visit(MethodDeclaration n, List<CallEdge> edges) {
        String previous = currentMethodName;
        currentMethodName = n.getNameAsString();
        super.visit(n, edges);
        currentMethodName = previous;
    }

    @Override
    public void visit(MethodCallExpr n, List<CallEdge> edges) {
        edges.add(CallEdge.builder()
                .callerClass(currentClassName)
                .callerMethod(currentMethodName)
                .calleeMethod(n.getNameAsString())
                .calleeScope(n.getScope().map(Object::toString).orElse(null))
                .build());
        super.visit(n, edges);
    }
}
