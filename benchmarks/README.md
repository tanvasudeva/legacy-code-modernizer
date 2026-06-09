# Benchmark Repositories

20 open-source Java projects used to evaluate the legacy-code-modernizer pipeline.
All cloned with `--depth=1`. Directories are gitignored.

## Original 10

| Repo | Approx LOC | Domain | Source root |
|---|---|---|---|
| spring-petclinic | 5k | Canonical Spring test case | `spring-petclinic/src` |
| HikariCP | 15k | JDBC connection pool | `HikariCP/src` |
| jhipster-sample-app | 20k | Standard enterprise app | `jhipster-sample-app/src` |
| jforum3 | 40k | Forum application | `jforum3/src` |
| zxing | 60k | Barcode processing | `zxing/core/src` |
| BroadleafCommerce | 80k | E-commerce monolith | `BroadleafCommerce/core/src` |
| openl-tablets | 100k | Business rules engine | `openl-tablets/DEV/modules/rules/src` |
| Activiti | 150k | BPM engine | `Activiti/activiti-engine/src` |
| openmrs-core | 200k | Healthcare records | `openmrs-core/api/src` |
| dbeaver | 500k | IDE (sparse checkout) | `dbeaver/plugins/org.jkiss.dbeaver.model/src` |

## Extended 10

| Repo | Approx LOC | Domain | Source root | Java files |
|---|---|---|---|---|
| retrofit | 15k | Type-safe HTTP client | `retrofit/retrofit/src/main/java` | 54 |
| gson | 30k | JSON serialization | `gson/gson/src/main/java` | 86 |
| caffeine | 20k | High-performance cache | `caffeine/caffeine/src/main/java` | 50 |
| resilience4j | 30k | Fault-tolerance patterns | `resilience4j/resilience4j-core/src/main/java` | 65 |
| mybatis-3 | 80k | ORM framework | `mybatis-3/src/main/java` | 393 |
| RxJava | 50k | Reactive extensions (JVM) | `RxJava/src/main/java` | 879 |
| okhttp | 50k | HTTP client (Kotlin-first) | `okhttp/okhttp/src` | 2† |
| flyway | 100k | DB migration tool | `flyway/flyway-core/src/main/java` | 470 |
| micrometer | 60k | Application metrics | `micrometer/micrometer-core/src/main/java` | 366 |
| guava | 200k | Google Core Libraries | `guava/guava/src` | 610 |

† okhttp is Kotlin-first; Java file count reflects only the thin Java API layer.
LOC figures are approximate and include all languages in the repo.

## Clone commands

```bash
cd benchmarks/

# Original 10
git clone --depth=1 https://github.com/spring-projects/spring-petclinic
git clone --depth=1 https://github.com/brettwooldridge/HikariCP
git clone --depth=1 https://github.com/jhipster/jhipster-sample-app
git clone --depth=1 https://github.com/andowson/jforum3
git clone --depth=1 https://github.com/zxing/zxing
git clone --depth=1 https://github.com/BroadleafCommerce/BroadleafCommerce
git clone --depth=1 https://github.com/openl-tablets/openl-tablets
git clone --depth=1 https://github.com/Activiti/Activiti
git clone --depth=1 https://github.com/openmrs/openmrs-core
git clone --depth=1 https://github.com/dbeaver/dbeaver

# Extended 10
git clone --depth=1 https://github.com/square/retrofit
git clone --depth=1 https://github.com/google/gson
git clone --depth=1 https://github.com/ben-manes/caffeine
git clone --depth=1 https://github.com/resilience4j/resilience4j
git clone --depth=1 https://github.com/mybatis/mybatis-3
git clone --depth=1 https://github.com/ReactiveX/RxJava
git clone --depth=1 https://github.com/square/okhttp
git clone --depth=1 https://github.com/flyway/flyway
git clone --depth=1 https://github.com/micrometer-metrics/micrometer
git clone --depth=1 https://github.com/google/guava
```
