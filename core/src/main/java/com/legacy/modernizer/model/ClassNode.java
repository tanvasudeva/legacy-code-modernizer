package com.legacy.modernizer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassNode {
    private String className;
    private String packageName;
    /** package.OuterClass.InnerClass (or package.ClassName for top-level). */
    private String fullyQualifiedName;
    /** Simple name of the superclass, null if none beyond Object. */
    private String superclass;
    private boolean isInterface;
    private boolean isAbstract;

    @Builder.Default private List<String> interfaces = new ArrayList<>();
    @Builder.Default private List<MethodInfo> methods   = new ArrayList<>();
    @Builder.Default private List<String> imports       = new ArrayList<>();
}
