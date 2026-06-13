package com.legacy.modernizer.eval.model;

/** Evaluation metrics produced by Phases 4.3, 4.7, and DD2. */
public enum MetricName {
    COMPILATION_SUCCESS,
    COMPILATION_FIRST_ATTEMPT,
    COMPILATION_POST_REPAIR,
    COVERAGE,
    API_COMPLETENESS,
    LLM_JUDGE_SCORE,
    SHARED_CLASS_DUPLICATION_RATE
}
