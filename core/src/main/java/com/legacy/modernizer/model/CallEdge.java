package com.legacy.modernizer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallEdge {
    /** Fully-qualified name of the class that contains the call site. */
    private String callerClass;
    /** Method in callerClass where the call was made. */
    private String callerMethod;
    /** Name of the method being called. */
    private String calleeMethod;
    /**
     * The scope expression the call is made on (e.g. "ownerService", "this").
     * Null for bare method calls in the same class.
     */
    private String calleeScope;
}
