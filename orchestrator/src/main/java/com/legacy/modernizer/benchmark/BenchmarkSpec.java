package com.legacy.modernizer.benchmark;

import java.nio.file.Path;

/**
 * Describes one benchmark target — the repo name, its Java source root, and
 * an approximate line-count used for progress logging.
 */
public record BenchmarkSpec(
        String name,
        Path   srcDirectory,
        int    approxLoc
) {
    /** Canonical list of all 20 benchmark repositories (paths relative to project root). */
    public static final java.util.List<BenchmarkSpec> ALL = java.util.List.of(
            // Original 10
            of("spring-petclinic",   "benchmarks/spring-petclinic/src",             5_000),
            of("HikariCP",           "benchmarks/HikariCP/src",                    15_000),
            of("jhipster-sample-app","benchmarks/jhipster-sample-app/src",          20_000),
            of("jforum3",            "benchmarks/jforum3/src",                      40_000),
            of("zxing",              "benchmarks/zxing/core/src",                   60_000),
            of("BroadleafCommerce",  "benchmarks/BroadleafCommerce",                80_000),
            of("openl-tablets",      "benchmarks/openl-tablets/DEV/modules/rules/src",100_000),
            of("Activiti",           "benchmarks/Activiti/activiti-engine/src",    150_000),
            of("openmrs-core",       "benchmarks/openmrs-core/api/src",            200_000),
            of("dbeaver",            "benchmarks/dbeaver/plugins/org.jkiss.dbeaver.model/src", 500_000),
            // Extended 10
            of("retrofit",           "benchmarks/retrofit/retrofit/src/main/java",  15_000),
            of("gson",               "benchmarks/gson/gson/src/main/java",           30_000),
            of("caffeine",           "benchmarks/caffeine/caffeine/src/main/java",   20_000),
            of("resilience4j",       "benchmarks/resilience4j/resilience4j-core/src/main/java", 30_000),
            of("mybatis-3",          "benchmarks/mybatis-3/src/main/java",           80_000),
            of("RxJava",             "benchmarks/RxJava/src/main/java",              50_000),
            of("okhttp",             "benchmarks/okhttp/okhttp/src",                 50_000),
            of("flyway",             "benchmarks/flyway/flyway-core/src/main/java", 100_000),
            of("micrometer",         "benchmarks/micrometer/micrometer-core/src/main/java", 60_000),
            of("guava",              "benchmarks/guava/guava/src",                  200_000)
    );

    private static BenchmarkSpec of(String name, String relPath, int loc) {
        return new BenchmarkSpec(name, Path.of(relPath), loc);
    }

    /** Resolves {@link #srcDirectory} relative to the given project root. */
    public Path resolveSrc(Path projectRoot) {
        return projectRoot.resolve(srcDirectory).normalize();
    }
}
