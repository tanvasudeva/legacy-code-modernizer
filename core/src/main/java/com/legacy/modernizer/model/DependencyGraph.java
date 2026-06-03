package com.legacy.modernizer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DependencyGraph {

    private final String sourceDirectory;
    private final List<ClassNode> nodes = new ArrayList<>();
    private final List<CallEdge> edges  = new ArrayList<>();

    public DependencyGraph(String sourceDirectory) {
        this.sourceDirectory = sourceDirectory;
    }

    public void addClass(ClassNode node) { nodes.add(node); }
    public void addEdge(CallEdge edge)   { edges.add(edge); }

    public List<ClassNode> getNodes()        { return nodes; }
    public List<CallEdge>  getEdges()        { return edges; }
    public String getSourceDirectory()       { return sourceDirectory; }

    public Optional<ClassNode> findByFqn(String fqn) {
        return nodes.stream().filter(n -> fqn.equals(n.getFullyQualifiedName())).findFirst();
    }

    @Override
    public String toString() {
        return "DependencyGraph{nodes=" + nodes.size() + ", edges=" + edges.size() + "}";
    }
}
