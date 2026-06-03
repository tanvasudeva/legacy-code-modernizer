package com.legacy.modernizer.visitor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts all import statements from a CompilationUnit.
 */
public class ImportVisitor extends VoidVisitorAdapter<List<String>> {

    public List<String> extract(CompilationUnit cu) {
        List<String> imports = new ArrayList<>();
        visit(cu, imports);
        return imports;
    }

    @Override
    public void visit(ImportDeclaration n, List<String> imports) {
        imports.add(n.getNameAsString() + (n.isAsterisk() ? ".*" : ""));
        super.visit(n, imports);
    }
}
