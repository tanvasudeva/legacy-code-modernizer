package com.legacy.modernizer.eval.model;

/** Evaluation metrics produced by Phases 4.3, 4.7, DD2, and DD3. */
public enum MetricName {
    COMPILATION_SUCCESS,
    COMPILATION_FIRST_ATTEMPT,
    COMPILATION_POST_REPAIR,
    COVERAGE,
    API_COMPLETENESS,
    LLM_JUDGE_SCORE,
    SHARED_CLASS_DUPLICATION_RATE,
    /** Fraction of CALLS edges that cross service boundaries (lower is better). */
    INTER_SERVICE_COUPLING,
    /** Average LCOM4 across all generated classes (lower is better; 1.0 = ideal). */
    AVG_LCOM4,
    /** Fraction of generated classes with LCOM4 = 1 (higher is better). */
    PERFECT_COHESION_PCT
}
