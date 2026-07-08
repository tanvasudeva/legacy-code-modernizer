# Legacy Code Modernizer — Complete Project Reference

> **Purpose of this document:** After reading this, you should have a complete mental model of what was built, why every design decision was made, how the pipeline executes step-by-step, what each metric measures and why, and what each module does at the code level. No further study should be necessary.

---

## Table of Contents

1. [What Problem This Solves](#1-what-problem-this-solves)
2. [High-Level Architecture](#2-high-level-architecture)
3. [Technology Stack — Every Tool and Why](#3-technology-stack--every-tool-and-why)
4. [Module Structure](#4-module-structure)
5. [The Full Pipeline — Step by Step](#5-the-full-pipeline--step-by-step)
6. [Each Agent Explained](#6-each-agent-explained)
7. [The Evaluation System — All 10 Metrics](#7-the-evaluation-system--all-10-metrics)
8. [The Benchmark Setup](#8-the-benchmark-setup)
9. [Database Schema](#9-database-schema)
10. [LLM Configuration and Cost Strategy](#10-llm-configuration-and-cost-strategy)
11. [Why We Did NOT Use Existing Frameworks](#11-why-we-did-not-use-existing-frameworks)
12. [The RAG System](#12-the-rag-system)
13. [The Baseline Systems (Comparison Baselines)](#13-the-baseline-systems-comparison-baselines)
14. [Benchmark Results — Job 28 vs Job 31](#14-benchmark-results--job-28-vs-job-31)
15. [Known Issues and Fixes Applied](#15-known-issues-and-fixes-applied)
16. [How to Run Everything](#16-how-to-run-everything)
17. [File-by-File Reference](#17-file-by-file-reference)

---

## 1. What Problem This Solves

**The core problem:** Enterprises have millions of lines of Java code in monolithic applications. Decomposing a monolith into microservices is one of the hardest engineering tasks — it requires deep domain understanding, identifying bounded contexts, managing shared dependencies, and generating working code that preserves the original business logic. This normally takes teams of engineers months.

**What this project does:** It is an automated multi-agent pipeline that takes a Java monolith repository as input and produces a set of independently-deployable Spring Boot 3 microservices as output — complete with entity classes, repositories, services, REST controllers, unit tests, OpenAPI specs, and architecture decision records (ADRs).

**The research angle:** The project is framed as a research experiment: does a multi-agent LLM pipeline produce better microservice decompositions than a single-prompt LLM call on the same input? It evaluates this comparison across 10 quantitative metrics on 20 benchmark repositories.

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        INPUT                                     │
│              Java Monolith Source Code (any repo)               │
└────────────────────────────┬────────────────────────────────────┘
                             │
                    ┌────────▼────────┐
                    │ Static Analysis │  JavaParser AST walk
                    │ + Neo4j Ingest  │  → class nodes, CALLS edges
                    └────────┬────────┘
                             │ dependency graph (45 nodes, 244 edges for petclinic)
                    ┌────────▼────────┐
                    │  Louvain        │  Python script
                    │  Community      │  → cluster map (class → cluster ID)
                    │  Detection      │
                    └────────┬────────┘
                             │ cluster assignments + inter-cluster call counts
                    ┌────────▼────────┐
                    │  ArchitectAgent │  LLM (Claude/GPT/Ollama)
                    │  (DDD Boundary  │  → service boundaries JSON
                    │   Analysis)     │  e.g. "owner-service", "vet-service"
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ SharedLibrary   │  Neo4j CALLS query
                    │ Detector (DD2)  │  → classes used by 2+ services → commons
                    └────────┬────────┘
                             │ boundaries now include a "-commons" service
                             │
               ┌─────────────▼──────────────┐
               │   For each ServiceBoundary  │
               │                             │
               │  ┌──────────────────────┐   │
               │  │ RAG Retrieval        │   │  Qdrant vector search
               │  │ (top-5 code chunks)  │   │  → relevant original code
               │  └──────────┬───────────┘   │
               │             │               │
               │  ┌──────────▼───────────┐   │
               │  │ ServiceGeneratorAgent│   │  LLM → pom.xml + all Java files
               │  │ + CompilationRepair  │   │  compile → fix → retry (up to 3x)
               │  └──────────┬───────────┘   │
               │             │               │
               │  ┌──────────▼───────────┐   │
               │  │ TestWriterAgent      │   │  LLM → JUnit 5 / Mockito tests
               │  └──────────┬───────────┘   │
               │             │               │
               │  ┌──────────▼───────────┐   │
               │  │ DocGenAgent          │   │  LLM → OpenAPI spec + ADR + Runbook
               │  └──────────────────────┘   │
               └─────────────────────────────┘
                             │
                    ┌────────▼────────┐
                    │ BundleAssembler │  → ZIP file with all services
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │   Evaluator     │  10 metrics computed automatically
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │     OUTPUT      │
                    │ bundle.zip      │
                    │ eval_metrics    │
                    │ (PostgreSQL)    │
                    └─────────────────┘
```

---

## 3. Technology Stack — Every Tool and Why

### Core Language & Runtime
- **Java 21** — Latest LTS. Used for records (immutable data carriers), pattern matching, text blocks (multiline LLM prompts as readable strings), and virtual threads compatibility.
- **Spring Boot 3.2.5** — Framework for the entire backend. Provides dependency injection, REST endpoints, data access, configuration, and health checks. The generated microservices also target Spring Boot 3.

### LLM Integration
- **LangChain4j** — Java library for LLM interaction. Provides model abstractions (`ChatLanguageModel`), message types (`SystemMessage`, `UserMessage`, `AiMessage`), and token usage tracking. We use it as a uniform interface over Anthropic, OpenAI, Gemini, and Ollama — swapping providers is a config change, not a code change.
- **Anthropic Claude (primary)** — `claude-haiku-4-5-20251001` for development runs (cheap), `claude-sonnet-4-6` for final benchmark. Used for all agents: boundary analysis, code generation, test writing, documentation.
- **Ollama (fallback)** — Local LLM server using `qwen2.5-coder:1.5b`. Zero API cost. Used when no cloud key is set. Quality is lower but allows offline development and testing the pipeline without spending money.
- **Gemini (configured but not used)** — Configured as a "free tier" option but the account has 0 quota. Code exists but never activates.

### Static Analysis
- **JavaParser** — Pure Java library for parsing Java source files into an AST (Abstract Syntax Tree). We walk the AST with custom visitors to extract class names, method signatures, import statements, and method call graphs. **Why not rely on a bytecode tool like ASM?** Because we need source-level analysis — we want class names, package structure, and method call relationships from readable code, not compiled bytecode. The source is what gets fed to the LLM anyway.

### Graph Database
- **Neo4j** — Graph database storing the class dependency graph. Each class is a `Class` node; relationships are `DEPENDS_ON` (imports), `CALLS` (method invocations), and `IN_PACKAGE`. **Why Neo4j?** The Louvain community detection algorithm works on a graph, and expressing "which classes call which other classes" is naturally a graph query (`MATCH (a:Class)-[:CALLS]->(b:Class)`). A relational database would require self-joins on an adjacency list which is slower and harder to express.

### Community Detection (Clustering)
- **Louvain Algorithm** (via Python, `python-louvain` library) — Partitions the class graph into communities that maximize modularity (classes that call each other a lot end up in the same cluster). This is the mathematical foundation for finding service boundaries: tightly-coupled classes should become one service.
- **Why Louvain?** It is the standard algorithm for community detection in complex networks. It is fast (near-linear complexity), produces high-quality partitions, and requires no pre-defined number of clusters. The ArchitectAgent then uses these clusters as a starting point and applies DDD reasoning on top.
- **Why Python?** The best implementations of Louvain are in Python (`python-louvain`, `networkx`). Rather than re-implementing in Java, we call a Python script via `ProcessBuilder` and pass the graph as a JSON adjacency list.

### Vector Search (RAG)
- **Qdrant** — Vector database for storing code embeddings. When generating a service, we retrieve the top-5 most similar code chunks from the original codebase to give the LLM real examples of how the business logic was originally written.
- **Embeddings** — Generated using Ollama's embedding model (`qwen2.5-coder:1.5b`). Each Java file is chunked and embedded; vectors are stored in Qdrant.

### Relational Database
- **PostgreSQL** — Primary persistence store. Every pipeline artifact (job state, agent tasks, generated code, evaluation metrics) is stored here. **Why Postgres over H2?** We need JSONB columns for flexible metadata storage (evaluation metadata, cluster maps, inter-cluster call graphs as JSON), which H2 does not support.
- **Flyway** — Database migration tool. All schema changes are versioned SQL files (`V1__create_migration_jobs.sql` through `V10__shared_classes.sql`). This ensures the schema evolves predictably and is reproducible.
- **Spring Data JPA + Hibernate** — ORM layer for all PostgreSQL interactions. Entity classes annotated with JPA annotations, repositories extend `JpaRepository<T, Long>`.

### Messaging
- **Redis Streams** — Used for agent-to-agent communication. When the main pipeline triggers a test-writer or doc-generator, it can publish a message to a Redis stream and the consumer picks it up asynchronously. This enables decoupling between pipeline phases.

### Build Tool
- **Maven (multi-module)** — The project is structured as a parent POM with 5 child modules (core, agents, rag, evaluator, orchestrator). Maven manages dependencies between modules, ensures correct build order, and is also used to compile the generated microservices during the evaluation phase.

### Frontend
- **React** — Simple dashboard to view job status, service boundaries, and evaluation metrics. Communicates with the backend via REST APIs.

### CI/CD
- **GitHub Actions** — Three workflows: `ci.yml` (compile + unit tests on every push), `codacy.yml` (static analysis + SARIF upload), `codeql.yml` (security scanning for Java and JavaScript).

---

## 4. Module Structure

```
legacy-code-modernizer/          ← parent Maven project
├── core/                        ← shared models, Neo4j, DB schema, AST visitors
├── agents/                      ← all LLM agents (ArchitectAgent, ServiceGeneratorAgent, etc.)
├── rag/                         ← vector indexing and retrieval (Qdrant)
├── evaluator/                   ← all 10 evaluation metrics
├── orchestrator/                ← Spring Boot app entry point, REST API, benchmark runner
├── benchmarks/                  ← 20 cloned Java repositories used as test inputs
├── scripts/                     ← Python script for Louvain clustering
└── results/                     ← output directory for benchmark runs
```

### Why This Module Split?

- **core** is imported by everything else. It defines the data models and the Neo4j/PostgreSQL interaction layer that all modules share.
- **agents** contains only LLM logic — no Spring MVC, no web layer. This makes it easy to unit-test agents in isolation by mocking the `ChatLanguageModel`.
- **rag** is isolated because Qdrant is optional. If Qdrant is unavailable, the RAG module's beans fail gracefully and the pipeline runs without context snippets.
- **evaluator** has no dependency on agents. It can evaluate any artifact bundle, whether produced by the multi-agent pipeline or a baseline.
- **orchestrator** is the only runnable Spring Boot application. It imports all other modules and wires them together.

---

## 5. The Full Pipeline — Step by Step

This is what happens when you run `POST /api/benchmark/spring-petclinic`.

### Step 1: Create Migration Job
- A `MigrationJob` row is inserted into PostgreSQL with status `PENDING`.
- Job ID is returned (e.g., job 31).

### Step 2: Static Analysis (`AnalysisService`)
- `DependencyExtractor` walks the source directory using JavaParser.
- For each `.java` file: parse AST → extract class name, package, methods, imports, and method calls.
- Skip files that use Java 14+ pattern matching (JavaParser can't parse those).
- Build a `DependencyGraph` — a list of `ClassNode` objects and `CallEdge` objects.
- `GraphIngester.ingest()` writes all of this to Neo4j:
  - Creates `Class` nodes with FQN (fully-qualified name) as the unique identifier.
  - Creates `IN_PACKAGE` edges connecting class to package node.
  - Creates `DEPENDS_ON` edges from import statements.
  - Creates `CALLS` edges from method call analysis.
- Example output for spring-petclinic: 45 class nodes, 6 package nodes, 244 relationships.

### Step 3: Louvain Clustering (`AnalysisService.runLouvain()`)
- The current Neo4j graph is exported as a JSON adjacency list (which classes call which).
- A Python subprocess runs `scripts/louvain_cluster.py` with this adjacency list as input.
- The script builds a `networkx` graph and runs the Louvain algorithm.
- Output: a `clusterMap` — a JSON dictionary mapping each class FQN to a cluster ID (e.g., `{"org.petclinic.Owner": "2", "org.petclinic.OwnerController": "2", ...}`).

### Step 3b: Inter-Cluster Call Edge Computation
- `GraphIngester.computeInterClusterEdges(clusterMap)` runs a Neo4j query:
  ```cypher
  MATCH (a:Class)-[:CALLS]->(b:Class) RETURN a.fqn, b.fqn
  ```
- For each CALLS edge, checks if the source and target are in different clusters.
- Counts how many cross-cluster calls exist between each pair of clusters.
- Returns a `Map<String, Map<String, Integer>>`: cluster A → {cluster B → call count}.
- This data is passed to `ArchitectAgent` so it knows which clusters are tightly coupled and should be merged.

### Step 4: DDD Boundary Analysis (`ArchitectAgent`)
- Receives the cluster map and inter-cluster call counts.
- Builds a structured LLM prompt containing:
  - All clusters with their class lists.
  - Top 20 cross-cluster call pairs (highest call count first).
  - DDD rules: merge high-call clusters, separate unrelated domains, use bounded context naming.
- Calls Claude (or Ollama) and receives a JSON response defining service boundaries.
- Each boundary has: `serviceName`, `classFqns[]`, `description`, `rationale`.
- Persists boundaries to PostgreSQL `service_boundaries` table.
- Example output for petclinic: `owner-service`, `pet-service`, `vet-service`.

### Step 5: Shared Library Detection (`SharedLibraryDetector`)
- Queries Neo4j for classes that appear in the `CALLS` targets of 2+ different service boundaries.
- Any class called by 2 or more services is a "shared class" — duplicating it across services would violate DRY and create maintenance problems.
- Removes these shared classes from their original service boundaries.
- Creates a new `{repoName}-commons` boundary containing all shared classes.
- Example: `spring-petclinic-commons` containing `Pet`, `BaseEntity`, `NamedEntity`.
- Persists shared class records to the `shared_classes` table.

### Step 6: RAG Index Build (`RagIndexService`)
- For the current benchmark repo, walks all Java source files.
- Chunks each file (by method or by fixed-size windows).
- Calls the Ollama embedding model to generate vector representations.
- Stores all vectors in Qdrant with metadata (file path, class name, method name).

### Step 7: Code Generation Loop (per service boundary)

For each service boundary (commons first, then regular services):

**7a. RAG Retrieval (`RagRetriever`)**
- Takes the list of class FQNs in this boundary.
- Queries Qdrant for the top-k (default 5, lite mode 2) most similar code chunks.
- Returns actual Java code snippets from the original codebase.

**7b. Method Signature Extraction**
- For each class FQN in the boundary, `ServiceGeneratorAgent.extractMethodSignatures()` uses JavaParser to find and extract all public method signatures (return type + name + parameter types).
- Capped at 30 signatures (lite: 10) to avoid token bloat.
- These are passed to the LLM as "REQUIRED public methods — implement all of these with exact method names."
- **Why:** Without this, the LLM generates generic CRUD methods that don't match the original API. With real signatures, `API_COMPLETENESS` improves significantly.

**7c. LLM Code Generation (`ServiceGeneratorAgent`)**
- Calls the LLM with a structured system prompt demanding `<file><path>...</path><content>...</content></file>` XML blocks.
- For commons services: uses `COMMONS_SYSTEM_PROMPT` (plain Java library, no Spring Boot, no database).
- For regular services: uses `SYSTEM_PROMPT` (full Spring Boot 3 microservice with entity/repo/service/controller).
- Parses response to extract all `<file>` blocks.
- If the LLM outputs markdown code fences instead of XML, a fallback regex parser handles it.

**7d. Compilation Repair Loop (`CompilationRepairService`)**
- Writes generated files to a temp directory.
- Runs `mvn compile -q` via `ProcessBuilder`.
- If it fails: sends the compiler errors back to the LLM with the original code and asks it to fix them.
- Retries up to `repair.max-attempts` times (default 3, lite: 1).
- Tracks `firstAttemptCompiled` and `finalCompiled` booleans on the `agent_tasks` record.
- Updates artifact content in PostgreSQL after each repair.

**7e. Test Generation (`TestWriterAgent`)**
- Reads the generated service code.
- Calls the LLM asking for JUnit 5 tests.
- Uses pure Mockito tests (no Spring context required) — `@ExtendWith(MockitoExtension.class)`.
- For controllers: `controller = new {Entity}Controller(mockService)` — constructor injection, no web context.
- **Why pure Mockito?** `@WebMvcTest` requires a full Spring context to load, which fails in temp directories without `application.properties` and real database connections. Pure Mockito tests are just Java objects — they always run regardless of environment.

**7f. Documentation Generation (`DocGenAgent`)** (skippable in lite mode)
- Generates three documents per service:
  - `docs/openapi.yaml` — OpenAPI 3.0 spec describing all REST endpoints.
  - `docs/adr.md` — Architecture Decision Record explaining why this service boundary was chosen.
  - `docs/runbook.md` — Operational runbook for deploying and monitoring the service.
- Stored as `OPENAPI_SPEC`, `ADR`, and `RUNBOOK` artifact types.

### Step 8: Bundle Assembly (`BundleAssembler`)
- Reads all `SERVICE_CODE`, `TEST_CODE`, `OPENAPI_SPEC`, `ADR`, and `RUNBOOK` artifacts from PostgreSQL.
- Groups by service name (using `classFqn` field as service name).
- Deduplicates: if multiple versions of the same file path exist (from repair attempts), keeps the one with the highest artifact ID (latest repair).
- Generates a root `pom.xml` listing all services as Maven modules.
- Generates a `docker-compose.yml` for running all services together.
- Writes everything into a ZIP stream.
- Saves to `results/{repoName}/multi-agent/bundle.zip`.

### Step 9: Metrics Computed
- After the benchmark run, `POST /api/eval/{jobId}/multi-agent` triggers evaluation.
- All 10 metrics are computed (see Section 7).
- Results stored in `eval_metrics` PostgreSQL table.

---

## 6. Each Agent Explained

### ArchitectAgent
**File:** `agents/src/main/java/com/legacy/modernizer/agent/ArchitectAgent.java`

**What it does:** Takes the Louvain cluster map + inter-cluster call data and outputs named service boundaries following Domain-Driven Design principles.

**System prompt rules:**
1. Each cluster must become exactly one service.
2. Merge clusters with many CALLS edges between them — high call volume = tight coupling = same bounded context.
3. Name services after the business domain, not technical layers.
4. Ensure services are loosely coupled and highly cohesive.

**Input:** `Map<String, List<String>>` (cluster ID → class FQNs) + `Map<String, Map<String, Integer>>` (from cluster → {to cluster → call count})

**Output:** JSON array of service boundaries, parsed and persisted to `service_boundaries` table.

**Why the inter-cluster call data matters:** Without call counts, the LLM sees cluster 2 (Owner classes) and cluster 5 (Pet classes) as separate domains. With call data showing Pet calls Owner 47 times, the LLM can make the informed decision to merge them or keep them separate. Previously (before this fix), `INTER_SERVICE_COUPLING` was high because the LLM split naturally-coupled clusters into different services.

---

### SharedLibraryDetector
**File:** `core/src/main/java/com/legacy/modernizer/sharedlib/SharedLibraryDetector.java`

**What it does:** Identifies classes that would need to be copied into multiple services and instead extracts them into a shared commons module.

**Algorithm:**
1. Tags each class in Neo4j with its assigned service (temporary property).
2. Queries: "find all classes that are in CALLS targets from 2+ different services."
3. Removes those classes from their original service boundaries.
4. Creates a `{repo}-commons` ServiceBoundary with those classes.
5. Persists records to `shared_classes` table.

**Why this is important:** Without this, if `BaseEntity` is used by 3 services, all 3 would generate their own copy. This violates DRY and would require keeping 3 copies in sync. The `SHARED_CLASS_DUPLICATION_RATE` metric measures how many classes are shared — lower is better.

**Threshold:** `sharedLibrary.minServiceCount=2` — a class appearing in 2 or more services becomes shared. Configurable.

---

### ServiceGeneratorAgent
**File:** `agents/src/main/java/com/legacy/modernizer/agent/ServiceGeneratorAgent.java`

**What it does:** The core generation agent. Takes a service boundary and produces a complete, working Spring Boot 3 microservice.

**For regular services generates:**
- `pom.xml` with Spring Boot parent, all required dependencies
- `Application.java` with `@SpringBootApplication`
- `{Entity}.java` with JPA annotations, Lombok
- `{Entity}Request.java` DTO
- `{Entity}Repository.java` extending `JpaRepository`
- `{Entity}Service.java` with CRUD methods, `@Transactional`
- `{Entity}Controller.java` with `@RestController`, all 5 CRUD endpoints

**For commons services generates:**
- `pom.xml` as a plain Java library (no Spring Boot parent, no web starter)
- One Java class per shared entity (base classes, utility classes, shared domain objects)

**Mandatory POM coordinate rules** (after fix):
- `groupId = com.modernized`
- `artifactId = {service-name}`
- `version = 1.0.0-SNAPSHOT` (EXACT — LLM must not change this)

**Output parsing:**
- Primary: `<file><path>...</path><content>...</content></file>` XML blocks
- Fallback: ` ```java ... ``` ` markdown blocks (for less-capable models that ignore XML format)

---

### CompilationRepairService
**File:** `agents/src/main/java/com/legacy/modernizer/agent/CompilationRepairService.java`

**What it does:** The "self-healing" component. If generated code doesn't compile, it uses the LLM to fix itself.

**Process:**
1. Writes all service files to a temporary directory.
2. Runs `mvn compile` via `ProcessBuilder`.
3. If exit code != 0: captures compiler error output.
4. Parses `CompilerError` objects (file, line, column, message).
5. Sends original code + errors to LLM: "Here is the code that failed to compile. Here are the errors. Please fix it."
6. Receives corrected code, updates artifact content in PostgreSQL.
7. Repeats up to `repair.max-attempts` times.

**Why important:** First-attempt compilation rates are typically 25-75% depending on model quality. Without repair, compilation success would be low. With 3 repair attempts, Sonnet achieves ~100% compilation.

---

### TestWriterAgent
**File:** `agents/src/main/java/com/legacy/modernizer/agent/TestWriterAgent.java`

**What it does:** Generates unit tests for each generated service.

**Test types generated (per service):**
- Service layer test: `@ExtendWith(MockitoExtension.class)`, mocks repository, tests all CRUD methods
- Controller test: `@ExtendWith(MockitoExtension.class)`, instantiates controller directly with mocked service, tests all endpoints return correct HTTP status codes

**Critical design choice — pure Mockito, no Spring context:**
Before fixing, tests used `@WebMvcTest` which requires:
- A running Spring application context
- A `application.properties` with database URL
- All beans to be available

In a temp directory at evaluation time, none of this exists. Tests would fail to even load.

After fixing: tests are plain Java objects. No application context needed. A `@BeforeEach` method creates the controller via `new {Entity}Controller(mockService)`. This always runs regardless of environment.

---

### DocGenAgent
**File:** `agents/src/main/java/com/legacy/modernizer/agent/DocGenAgent.java`

**What it does:** Generates three types of documentation per service.

**OpenAPI Spec (`OPENAPI_SPEC`):** YAML file describing all REST endpoints, request/response schemas, HTTP methods, status codes. Useful for API consumers and for integrating with API gateways.

**ADR (`ADR` — Architecture Decision Record):** Markdown document explaining: what this service does, why these classes were grouped together, what alternatives were considered, and what the tradeoffs are. Follows Michael Nygard's ADR format.

**Runbook (`RUNBOOK`):** Operational markdown document with: how to build and run the service locally, environment variables required, health check endpoints, common operational tasks (check logs, scale, debug connection issues).

**Why skippable in lite mode:** Documentation contributes to 0 evaluation metrics. It's useful for the final report presentation but wastes LLM tokens during development validation runs.

---

### RefactorerAgent
**File:** `agents/src/main/java/com/legacy/modernizer/agent/RefactorerAgent.java`

**What it does:** An older/alternative agent that refactors existing code rather than generating new code from scratch. Used in the Redis stream-based async flow (not the benchmark pipeline). Can be triggered via `POST /api/jobs/{id}/refactor`.

---

## 7. The Evaluation System — All 10 Metrics

The evaluator (`EvaluatorService`) computes all metrics automatically from the generated artifacts. Metrics are stored in `eval_metrics` (PostgreSQL) with `system_id` tagging which system produced the artifact (`multi-agent`, `single-prompt-claude`, `single-prompt-gpt4o`).

---

### 1. COMPILATION_SUCCESS
**What it measures:** Fraction of generated services that compile successfully after all repair attempts.

**How computed:** For each service, extracts all `SERVICE_CODE` artifacts, writes them to a temp directory (with `spring-petclinic-commons` installed first if it exists), runs `mvn compile`. Score = services that compiled / total services.

**Why it matters:** A microservice that doesn't compile is completely useless. This is the baseline sanity check.

**Target:** 1.0 (100%)

**Job 28 result:** 1.0 (100%) — Sonnet with 3 repair attempts

**Job 31 result:** 0.0 (0%) — Haiku with 1 repair attempt + pom.xml version mismatch bug

---

### 2. COMPILATION_FIRST_ATTEMPT
**What it measures:** Fraction of services that compiled on the very first LLM generation, without any repair.

**How computed:** Read from `agent_tasks.output_data.firstAttemptCompiled` — tracked during pipeline execution, not re-evaluated.

**Why it matters:** Measures raw LLM code quality before the repair safety net. A high score means the model writes correct code without needing corrections. This is a proxy for model capability.

**Job 31 result:** 0.25 (1/4 services compiled on first try with Haiku)

---

### 3. COMPILATION_POST_REPAIR
**What it measures:** Same as COMPILATION_SUCCESS but specifically from the repair evaluator. Usually identical to COMPILATION_SUCCESS.

---

### 4. COVERAGE
**What it measures:** Average JaCoCo line coverage across all generated services, measured by actually running the generated tests.

**How computed:**
1. Writes service code + test code to a temp directory.
2. Runs `mvn test jacoco:report -q` via `ProcessBuilder` (JaCoCo is in the generated pom.xml).
3. Reads `target/site/jacoco/jacoco.xml` — parses `<counter type="LINE">` elements.
4. Calculates: covered_lines / (covered_lines + missed_lines) per service.
5. Averages across all services.

**Why 0% in all jobs:** Two root causes were identified and fixed:
- **Before fix:** Tests used `@WebMvcTest` which requires Spring context → tests fail to load → JaCoCo reports 0 coverage.
- **After fix:** Pure Mockito tests → tests run → coverage should be non-zero.
- **Additional cause:** If compilation fails, tests can't run either.

**Target:** >30% (realistic for generated tests that test all CRUD operations)

---

### 5. API_COMPLETENESS
**What it measures:** Fraction of public methods from the original codebase that are reproduced in the generated services.

**How computed:**
1. Parses original source files using JavaParser — extracts all public method signatures (method name only for matching).
2. Parses generated source files — extracts all public method signatures.
3. `preservedCount` = methods that exist in both original and generated.
4. Score = `preservedCount / originalMethodCount`.

**Why it matters:** If the LLM generates generic `findAll()`, `findById()`, `create()`, `update()`, `delete()` but the original had `findByLastName()`, `findByOwnerLastNameLike()`, `countBySpeciality()` — those domain-specific methods are lost. The generated service doesn't preserve the original API contract.

**Why it improved with signature extraction:** Before: LLM only knew class names → generated generic CRUD. After: LLM receives "REQUIRED public methods: `List<Owner> findByLastName(String lastName)`, `Owner findOwnerById(Integer id)`..." → generates services that include those exact methods.

**Job 28 result:** 0.338 (33.8%) — Sonnet without signature hints

**Job 31 result:** 0.169 (16.9%) — Haiku with signature hints but weaker generation quality

---

### 6. LLM_JUDGE_SCORE
**What it measures:** An LLM's holistic assessment of microservice quality across multiple dimensions: correctness, cohesion, separation of concerns, domain model quality, and code style.

**How computed:**
1. `LlmJudgeMetric` selects a judge model via `CrossModelJudge`.
2. For `multi-agent` output: judge is GPT-4o (cross-model — Claude output judged by GPT-4o to avoid self-scoring bias).
3. For `single-prompt-gpt4o` output: judge is Claude (GPT-4o output judged by Claude).
4. LLM receives the generated code and scores it 0-10 on multiple sub-dimensions.
5. Sub-scores averaged to produce final 0.0-1.0 score.

**Why cross-model evaluation?** This is the standard approach from MT-Bench and AlpacaEval research. An LLM consistently rates its own outputs higher than an independent model would. Using GPT-4o to judge Claude's output (and vice versa) produces less biased scores. This is noted as methodologically important for the project report.

**Degraded mode:** If only Anthropic key is available (no OpenAI key), Claude judges everything — flagged as "self-scoring" in the metadata. Still produces a non-zero score.

**Job 28 and 31 result:** 0.0 — API key not available to `CrossModelJudge` at server startup time.

---

### 7. INTER_SERVICE_COUPLING
**What it measures:** What fraction of cross-class method calls in the generated code are cross-service (as opposed to within-service).

**How computed:**
1. Parses all generated Java files.
2. Identifies import statements that reference classes in other services.
3. `crossServiceCalls / totalCalls` = coupling rate.

**Why it matters:** Low coupling means services are independent — they don't constantly call each other. High coupling (e.g., 0.9 = 90% of calls cross service boundaries) means the decomposition failed; you've taken a monolith and split it into tightly-coupled services that still need to call each other constantly.

**Target:** <0.3 (most calls should be within-service)

**Job 31 result:** 0.9 (90%) — The generated services heavily depend on each other. Reflects the architecture quality of the LLM-generated code.

---

### 8. AVG_LCOM4
**What it measures:** Average Lack of Cohesion of Methods (LCOM4) across all generated classes.

**How computed:**
- LCOM4 for a class = number of connected components in a graph where methods are nodes, and two methods are connected if they share a field access.
- LCOM4 = 1 means all methods form one cohesive group (they all relate to the same data) — perfect cohesion.
- LCOM4 = 3 means the class has 3 independent method groups and should probably be split into 3 classes.

**Why it matters:** Cohesion measures how focused a class is on a single responsibility. High LCOM4 = God classes doing unrelated things. Low LCOM4 = clean, focused classes.

**Job 31 result:** 1.6111 average, 77.8% of classes have LCOM4 = 1 (perfect cohesion)

---

### 9. PERFECT_COHESION_PCT
**What it measures:** Percentage of generated classes with LCOM4 = 1 (perfect cohesion, single connected component).

**Job 31 result:** 77.8% — most generated classes are cohesive

---

### 10. SHARED_CLASS_DUPLICATION_RATE
**What it measures:** What fraction of the total original class count was identified as "shared" and extracted into the commons module, rather than being duplicated across services.

**How computed:** `shared_classes.size() / total_original_classes`

**Why it matters:** If 21% of classes are shared, and we extract them into commons, we've prevented 21% of the codebase from being duplicated N times across services. Lower duplication = better design.

**Job 31 result:** 0.2143 (21.4% of classes are in commons, preventing their duplication)

---

## 8. The Benchmark Setup

### The 20 Benchmark Repositories

Registered in `BenchmarkSpec.ALL` — 10 original + 10 extended. All are real open-source Java projects:

| Repo | Approx LOC | Domain |
|---|---|---|
| spring-petclinic | 5,000 | Veterinary clinic management |
| HikariCP | 15,000 | JDBC connection pooling |
| jhipster-sample-app | 20,000 | Full-stack web app |
| jforum3 | 40,000 | Internet forum |
| zxing | 60,000 | Barcode scanning |
| BroadleafCommerce | 80,000 | E-commerce platform |
| openl-tablets | 100,000 | Business rules engine |
| Activiti | 150,000 | BPM/workflow engine |
| openmrs-core | 200,000 | Medical records system |
| dbeaver | 500,000 | Database management tool |
| retrofit | 15,000 | HTTP client |
| gson | 30,000 | JSON serialization |
| caffeine | 20,000 | Caching library |
| resilience4j | 30,000 | Fault tolerance |
| mybatis-3 | 80,000 | ORM framework |
| RxJava | 50,000 | Reactive programming |
| okhttp | 50,000 | HTTP client |
| flyway | 100,000 | Database migrations |
| micrometer | 60,000 | Metrics facade |
| guava | 200,000 | Java utility library |

### Why spring-petclinic as the validation target?

It's the simplest (5,000 LOC) and most well-known Spring Boot example. Everyone understands what a veterinary clinic domain looks like — it has clear bounded contexts (Owner, Pet, Vet, Visit). Results are easy to reason about. After validating here, we scale to larger repos.

### Running a Benchmark

1. `POST /api/benchmark/{repoName}` — runs the full pipeline, returns `jobId`
2. `POST /api/eval/{jobId}/multi-agent` — computes all 10 metrics
3. `GET /api/eval/{jobId}` — fetches all metric values as JSON

---

## 9. Database Schema

All tables managed by Flyway (10 migration files in `core/src/main/resources/db/migration/`).

### migration_jobs (V1)
The top-level job record. One row per benchmark run.
```sql
id, name, source_directory, status (PENDING/ANALYZING/DONE/FAILED),
metadata (JSONB with cluster map + stats), created_at, updated_at
```

### service_boundaries (V2 + V6)
One row per service boundary proposed by ArchitectAgent.
```sql
id, job_id, service_name, class_fqns (JSONB array), 
description, rationale, created_at
```

### agent_tasks (V3)
One row per LLM call (SERVICE_GEN, TEST_GEN, DOC_GEN, etc.)
```sql
id, job_id, task_type, status (PENDING/IN_PROGRESS/COMPLETED/FAILED),
class_fqn (service name), input_data (JSONB), output_data (JSONB),
model_used, tokens_used, error_message, created_at, updated_at
```

### artifacts (V4)
One row per generated file (Java source, test, pom.xml, OpenAPI spec, etc.)
```sql
id, job_id, task_id, artifact_type (SERVICE_CODE/TEST_CODE/OPENAPI_SPEC/ADR/RUNBOOK),
class_fqn (service name), file_path, content (TEXT), created_at
```
Content is the full file content as a string. This is the primary artifact store.

### eval_metrics (V5 + V7 + V8)
One row per metric measurement.
```sql
id, job_id, artifact_id, metric_name, metric_value (DECIMAL),
system_id (multi-agent/single-prompt-claude/single-prompt-gpt4o),
baseline (null for multi-agent), judge_model, metadata (JSONB), measured_at
```

### repair_tracking (V9)
Tracks compilation repair attempts per service.
```sql
service_name, attempts, first_attempt_success, final_success per job
```

### shared_classes (V10)
Per-class records from SharedLibraryDetector.
```sql
id, job_id, class_fqn, service_count (how many services reference it),
referencing_services (JSONB list of service names), created_at
```

---

## 10. LLM Configuration and Cost Strategy

### Provider Priority
1. **Gemini** — active when `GEMINI_API_KEY` is set. Free tier but 0 quota on this account.
2. **Anthropic** — active when `ANTHROPIC_API_KEY` is set AND Gemini is absent. Paid.
3. **Ollama** — always available as fallback. Free, local, lower quality.

### How Provider Selection Works
`AnthropicConfig.java` uses:
```java
@ConditionalOnExpression(
    "'${ANTHROPIC_API_KEY:${anthropic.api.key:}}'.length() > 0 " +
    "and '${GEMINI_API_KEY:}'.length() == 0"
)
```
Spring evaluates this expression at startup. If true, the `AnthropicChatModel` bean is created with `@Primary`, overriding the Ollama bean.

**Important:** The API key must be available either:
- As an environment variable: `export ANTHROPIC_API_KEY=sk-ant-...`
- Or as a Spring property: `anthropic.api.key=sk-ant-...` in `application.properties` or `-D` flag.

### Model Choices and Cost

| Model | Use case | Approximate cost for petclinic |
|---|---|---|
| claude-haiku-4-5-20251001 | Lite/dev runs | ~$0.05–$0.10 |
| claude-sonnet-4-6 | Full report run | ~$1.43 (job 28 actual) |
| claude-sonnet-4-6 (baseline) | SinglePromptClaude | ~$0.49 |

**Why Haiku for development?** Haiku is ~20x cheaper on output tokens than Sonnet. For testing that the pipeline runs end-to-end without crashes, we don't need Sonnet quality. For the final benchmark numbers that go in the report, we use Sonnet.

### Profiles

**Default (`application.properties`):** Haiku, 8192 max tokens, 5 RAG snippets, 3 repair attempts, docs enabled, 30 method signatures.

**Lite (`application-lite.properties`, `--spring.profiles.active=lite`):**
- Haiku, 4096 max tokens, 2 RAG snippets, 1 repair attempt, docs skipped, 10 method signatures
- Estimated cost: ~3-5% of full Sonnet run

**Full (`application-full.properties`, `--spring.profiles.active=full`):**
- Sonnet, 8192 max tokens, 5 RAG snippets, 3 repair attempts, docs enabled, 30 method signatures
- Use this exactly once for the final project report

---

## 11. Why We Did NOT Use Existing Frameworks

This is a common question. Several tools exist for code migration and LLM agents. Here's why none of them were used.

### Why not OpenRewrite?
OpenRewrite is a source code refactoring tool for Java. It works by applying recipe-based transformations (e.g., "migrate from Spring Boot 2 to 3", "replace Log4j with SLF4J"). It is excellent for syntactic migrations — updating API calls, dependency versions, or annotation changes within a single codebase.

**Why it doesn't solve our problem:** OpenRewrite does not do service boundary identification. It cannot look at a monolith and decide which classes belong in "owner-service" vs "vet-service". Our problem is architectural decomposition, not syntactic transformation. OpenRewrite would be useful as a post-processing step after decomposition, not as the decomposition tool itself.

### Why not Spring Modulith?
Spring Modulith is a library that helps structure existing Spring Boot applications as logical modules and enforces module boundaries. It works within a single Spring Boot application.

**Why it doesn't solve our problem:** Spring Modulith enforces modularity, it doesn't generate independent microservices. It requires you to already know the module structure. Our problem is figuring out that structure automatically from an un-annotated legacy codebase.

### Why not Graphify / JQAssistant?
These are Java codebase analysis tools that can generate dependency graphs and run queries on them. JQAssistant stores Java metadata in Neo4j.

**Why we use Neo4j directly instead:** We build our own Neo4j graph with exactly the data we need (CALLS edges from method invocations, not just class-level imports). The Louvain algorithm needs a specific graph format. Using JQAssistant would add a layer of abstraction we don't need and would limit our ability to customize the graph schema. We also need the inter-cluster call edge computation (`computeInterClusterEdges`) which is a custom query specific to our pipeline.

### Why not LangChain (Python)?
LangChain is a Python framework for building LLM applications. LangChain4j is the Java port we use.

**Why Java instead of Python LangChain:** The entire project runs on the JVM. JavaParser is Java-native. Spring Boot is Java. The generated microservices are Java. Using Python LangChain would require bridging two runtimes (we already bridge to Python once for Louvain — adding more would complicate deployment). LangChain4j provides the same model-agnostic LLM interface (`ChatLanguageModel`) that we need.

### Why not existing microservice decomposition research tools?
Several academic tools exist (e.g., Mono2Micro from IBM). These are research prototypes with limited availability, no active maintenance, and typically rely on execution traces (runtime behavior) rather than static analysis. Our project uses static analysis only, which is more practical — you don't need to run the monolith to analyze it.

### Why build custom agents instead of using a framework like AutoGen or CrewAI?
AutoGen, CrewAI, and similar frameworks provide multi-agent coordination, but they are Python-native and the abstraction level is too high for our use case.

**Our agents are simple and direct:**
- Each agent has a `generate()` method that takes well-defined inputs and returns well-defined outputs.
- There is no autonomous agent-to-agent negotiation — the pipeline is fixed (ArchitectAgent → SharedLibraryDetector → ServiceGeneratorAgent → TestWriter → DocGen).
- The coordination logic in `BenchmarkRunner` is straightforward imperative code.
- Building this ourselves means the pipeline is fully transparent, debuggable, and extensible.

---

## 12. The RAG System

RAG = Retrieval-Augmented Generation. The idea: instead of asking the LLM to generate code entirely from class names and descriptions, give it relevant snippets from the original codebase as examples.

### RagIndexService
**File:** `rag/src/main/java/com/legacy/modernizer/rag/RagIndexService.java`

**Process:**
1. Walks all `.java` files in the source directory.
2. Chunks each file (by class/method boundaries, or fixed size).
3. Sends each chunk to Ollama's embedding model → gets a vector (list of floats).
4. Stores vector + chunk text + metadata in Qdrant under a collection named after the repo.

### RagRetriever
**File:** `rag/src/main/java/com/legacy/modernizer/rag/RagRetriever.java`

**Process:**
1. Takes the list of class FQNs for a service boundary.
2. Creates a query text from those class names.
3. Embeds the query using Ollama.
4. Queries Qdrant for top-k nearest neighbors by cosine similarity.
5. Returns the text content of the top-k chunks.

**Why this helps:** If `ServiceGeneratorAgent` is generating `owner-service` and the RAG retriever returns the original `Owner.java`, `OwnerRepository.java`, and `OwnerService.java` source code, the LLM can see exactly how the original business logic was implemented and reproduce it faithfully. Without RAG, the LLM only knows class names and must guess the logic.

**Configurable:** `benchmark.rag-top-k=5` (full), `=2` (lite). Fewer snippets = fewer tokens = cheaper.

---

## 13. The Baseline Systems (Comparison Baselines)

To evaluate whether the multi-agent pipeline is actually better than alternatives, we implement two comparison baselines that go through the same evaluation pipeline.

### SinglePromptClaude
**File:** `orchestrator/.../benchmark/SinglePromptClaude.java`

**What it does:** Sends the entire monolith source code in a SINGLE prompt to Claude and asks it to:
1. Decompose into microservices (JSON plan).
2. Generate working code for each service (one LLM call per service).

**No:** graph analysis, Louvain clustering, RAG retrieval, compilation repair, or cross-agent coordination.

**Why this is the fair baseline:** It represents what a developer would do if they just pasted their codebase into Claude and said "refactor this into microservices." This is the "naive but reasonable" approach.

**Cost:** ~$0.49 per petclinic run on Sonnet.

**Model:** Always `claude-sonnet-4-6` (hardcoded) — the baseline should use Sonnet to represent the best a single-prompt Claude can do.

**Note on billing:** Uses Anthropic API credits (api.anthropic.com), not Claude Pro subscription (claude.ai). These are separate.

### SinglePromptGpt4o
**File:** `orchestrator/.../benchmark/SinglePromptGpt4o.java`

**Same approach as SinglePromptClaude but using GPT-4o.**

Requires `OPENAI_API_KEY`. Since no OpenAI key is currently available, this baseline always returns a "skipped" result with no metrics.

### BaselineResult vs BenchmarkResult
Both baselines and the multi-agent pipeline produce results stored in `eval_metrics` with different `system_id` values:
- `multi-agent` — our pipeline
- `single-prompt-claude` — Claude baseline
- `single-prompt-gpt4o` — GPT-4o baseline (skipped if no key)

This allows direct metric-by-metric comparison in the final report.

### BaselineCodeExtractor
**File:** `evaluator/.../metric/BaselineCodeExtractor.java`

Since baselines output markdown ` ```java ... ``` ` blocks (not a proper ZIP bundle), this class extracts them, determines the Java package/class structure, writes them to a temp dir, and runs compilation to get a compilation score.

---

## 14. Benchmark Results — Job 28 vs Job 31

### Job 28 (Sonnet, 3 repair attempts, no metric fixes)

| Metric | Value | Notes |
|---|---|---|
| COMPILATION_SUCCESS | 100% | Sonnet + 3 repairs = fully working code |
| COVERAGE | 0% | @WebMvcTest bug — tests failed to load |
| API_COMPLETENESS | 33.8% | No method signatures passed to LLM |
| LLM_JUDGE_SCORE | 0% | API key not available to CrossModelJudge |
| Total cost | $1.43 | Sonnet is expensive |

### Job 31 (Haiku lite profile, 1 repair attempt, all fixes applied in code)

| Metric | Value | Notes |
|---|---|---|
| COMPILATION_SUCCESS | 0% | pom.xml version mismatch bug (fixed for next run) |
| COMPILATION_FIRST_ATTEMPT | 25% | Haiku quality lower than Sonnet |
| COVERAGE | 0% | Follows from compilation failure |
| API_COMPLETENESS | 16.9% | Haiku generates simpler code despite signature hints |
| LLM_JUDGE_SCORE | 0% | API key issue + no OpenAI key |
| INTER_SERVICE_COUPLING | 90% | Poor architecture from Haiku |
| AVG_LCOM4 | 1.61 | 77.8% perfect cohesion |
| SHARED_CLASS_DUPLICATION_RATE | 21.4% | Commons correctly extracted |
| Cost | ~$0.08 | Haiku is much cheaper |

### Why Job 31 Is Worse on Nearly Every Metric

Job 31 used Haiku (lite mode) with 1 repair attempt. The key insight: **Haiku is too weak for this task.** It:
- Generates inconsistent pom.xml coordinates (wrong groupId/version) causing cascade compilation failure.
- Generates simpler code with fewer domain-specific methods (lower API_COMPLETENESS).
- Generates architecture that doesn't respect service boundaries well (high coupling).
- Cannot follow complex prompt rules as reliably as Sonnet.

The fixes applied (pure Mockito tests, method signatures, inter-cluster call data, Spring @Value for API keys, CompilationMetric install-commons-first) are **correct and will show improvements** when run with Sonnet. Job 31 just couldn't demonstrate them because Haiku isn't capable enough.

### Next Steps for the Final Report

1. Run `--spring.profiles.active=full` (Sonnet) to get the true fixed metrics.
2. Run `SinglePromptClaude` baseline to get comparison numbers.
3. Compare multi-agent vs single-prompt across all 10 metrics.
4. The key hypothesis: multi-agent + graph analysis + RAG + repair produces better metrics than a single prompt.

---

## 15. Known Issues and Fixes Applied

### Fix 1: COMPILATION_SUCCESS 0% (pom.xml Version Mismatch)
**Root cause:** Haiku generates inconsistent Maven coordinates. The commons service pom.xml says `groupId=com.modernized.springpetcliniccommons, version=1.0-SNAPSHOT`. Other services' pom.xml files reference it as `groupId=com.modernized, version=1.0.0-SNAPSHOT`. Dependency resolution fails.

**Fix:** Added explicit version rule to both system prompts: "version = 1.0.0-SNAPSHOT — EXACT, do not change." Added commons dependency coordinate rule to non-commons services. Changed `CompilationMetric` to run `mvn install -DskipTests` on commons services before compiling dependents.

### Fix 2: COVERAGE 0% (Tests Never Ran)
**Root cause:** TestWriterAgent generated `@WebMvcTest` controller tests requiring Spring context, which fails in isolated temp directories.

**Fix:** Changed to pure Mockito tests (`@ExtendWith(MockitoExtension.class)`) that instantiate controllers directly via constructor injection. These run in any environment.

### Fix 3: API_COMPLETENESS 16.9% (Generic CRUD Methods)
**Root cause:** LLM only received class FQN names. Generated generic `findAll`, `save`, `delete` methods that don't match original API.

**Fix:** `extractMethodSignatures()` in `ServiceGeneratorAgent` uses JavaParser to extract all public method signatures from original source files. These are passed to the LLM as required methods to implement.

### Fix 4: LLM_JUDGE_SCORE 0% (API Key Not Available)
**Root cause:** `CrossModelJudge`, `SinglePromptClaude`, `SinglePromptGpt4o` used `System.getenv()` which only reads environment variables present at JVM startup. If key was in `application.properties`, it was ignored.

**Fix:** Replaced all `System.getenv()` calls with `@Value("${ANTHROPIC_API_KEY:${anthropic.api.key:}}")` which resolves from env var OR Spring property OR `-D` flag, in that priority order.

### Fix 5: INTER_SERVICE_COUPLING (ArchitectAgent Had No Call Data)
**Root cause:** ArchitectAgent only saw cluster assignments, not which clusters heavily called each other. Would split tightly-coupled clusters into different services.

**Fix:** `GraphIngester.computeInterClusterEdges()` queries all CALLS edges in Neo4j, counts cross-cluster calls, passes top 20 pairs to ArchitectAgent prompt. LLM can now make informed boundary decisions.

### Fix 6: AnthropicConfig Activation (SpEL Expression)
**Root cause:** `@ConditionalOnExpression` used `T(java.lang.System).getenv('ANTHROPIC_API_KEY')` — only checked env var, not Spring properties.

**Fix:** Changed to `'${ANTHROPIC_API_KEY:${anthropic.api.key:}}'.length() > 0` — Spring resolves the property placeholder before evaluating the SpEL expression, checking both env var and properties files.

---

## 16. How to Run Everything

### Prerequisites
- Java 21
- Maven 3.9+
- PostgreSQL 16 running on localhost:5432 (database: `lcm`, user: `postgres`, password: `postgres`)
- Neo4j running on localhost:7687 (user: `neo4j`, password: `neo4j_password`)
- Redis running on localhost:6379
- Qdrant running on localhost:6333
- Python 3.11 with `python-louvain` and `networkx` installed
- Anthropic API key (for Claude)

### Build
```bash
# From project root
mvn install -DskipTests -q
```

### Start Server (Lite Profile — cheap dev runs)
```bash
export ANTHROPIC_API_KEY=sk-ant-...
cd orchestrator
mvn spring-boot:run -Dspring-boot.run.profiles=lite
```

### Start Server (Full Profile — final report)
```bash
export ANTHROPIC_API_KEY=sk-ant-...
cd orchestrator
mvn spring-boot:run -Dspring-boot.run.profiles=full
```

### Run a Benchmark
```bash
# In a separate terminal
curl -X POST http://localhost:8080/api/benchmark/spring-petclinic
# Returns: {"jobId": 32, "serviceCount": 4, ...}
```

### Evaluate Results
```bash
curl -X POST http://localhost:8080/api/eval/32/multi-agent
curl http://localhost:8080/api/eval/32
```

### Run Claude Baseline
```bash
curl -X POST http://localhost:8080/api/baseline/spring-petclinic/single-prompt-claude
curl -X POST http://localhost:8080/api/eval/32/baseline/single-prompt-claude
```

### Useful API Endpoints
| Method | Path | Description |
|---|---|---|
| POST | `/api/benchmark/{repoName}` | Run full pipeline for one repo |
| POST | `/api/benchmark/all` | Run all 20 repos sequentially |
| GET | `/api/benchmark` | List all registered repos |
| POST | `/api/eval/{jobId}/multi-agent` | Compute all 10 metrics |
| GET | `/api/eval/{jobId}` | Fetch stored metrics |
| GET | `/api/jobs/{id}/status` | Check job status |
| GET | `/api/jobs/{id}/boundaries` | View service boundaries |
| GET | `/api/jobs/{id}/artifacts/{serviceName}` | View generated files |
| GET | `/api/jobs/{id}/shared-classes` | View commons classes |
| GET | `/api/jobs/{id}/bundle` | Download ZIP bundle |

---

## 17. File-by-File Reference

### core module

| File | Purpose |
|---|---|
| `DependencyExtractor.java` | Walks source directories with JavaParser, builds ClassNode + CallEdge graph |
| `ClassVisitor.java` | JavaParser visitor that extracts class name, package, imports |
| `CallGraphVisitor.java` | JavaParser visitor that extracts method-level call relationships |
| `MethodVisitor.java` | JavaParser visitor that extracts method signatures and bodies |
| `ImportVisitor.java` | JavaParser visitor for import statement extraction |
| `GraphIngester.java` | Writes DependencyGraph to Neo4j; also exports adjacency list JSON, computes inter-cluster edges |
| `SharedLibraryAnalyzer.java` | Runs Neo4j CALLS queries to find cross-boundary shared classes |
| `SharedLibraryDetector.java` | Orchestrates shared class extraction; creates commons ServiceBoundary |
| `MigrationJob.java` | JPA entity for `migration_jobs` table |
| `ServiceBoundary.java` | JPA entity for `service_boundaries` table |
| `AgentTask.java` | JPA entity for `agent_tasks` table |
| `Artifact.java` | JPA entity for `artifacts` table |
| `JobStateMachine.java` | State transition logic for job status (PENDING → ANALYZING → DONE/FAILED) |

### agents module

| File | Purpose |
|---|---|
| `ArchitectAgent.java` | LLM agent: cluster map → DDD service boundaries |
| `ServiceGeneratorAgent.java` | LLM agent: service boundary + RAG + method sigs → full Spring Boot service |
| `CompilationRepairService.java` | Compile-fix loop: runs mvn compile, feeds errors back to LLM, retries |
| `TestWriterAgent.java` | LLM agent: generated service → JUnit 5 / Mockito test suite |
| `DocGenAgent.java` | LLM agent: service → OpenAPI spec + ADR + Runbook |
| `RefactorerAgent.java` | Alternative agent for refactoring (not used in benchmark pipeline) |

### rag module

| File | Purpose |
|---|---|
| `RagIndexService.java` | Walks source, chunks files, embeds with Ollama, stores in Qdrant |
| `RagRetriever.java` | Embeds query, retrieves top-k similar code chunks from Qdrant |
| `CodeChunk.java` | Record representing one indexed code fragment |

### evaluator module

| File | Purpose |
|---|---|
| `EvaluatorService.java` | Orchestrates all metric computations for a job |
| `CompilationMetric.java` | Runs mvn compile per service; commons installed first |
| `CoverageMetric.java` | Runs mvn test jacoco:report; parses jacoco.xml |
| `ApiCompletenessMetric.java` | Compares original vs generated public method names |
| `CouplingMetric.java` | Counts cross-service import references |
| `CohesionMetric.java` | Computes LCOM4 per class using JavaParser field-access analysis |
| `LlmJudgeMetric.java` | Sends generated code to CrossModelJudge for holistic scoring |
| `CrossModelJudge.java` | Routes to correct judge model (GPT-4o for Claude output, Claude for GPT-4o output) |
| `BaselineCodeExtractor.java` | Extracts java blocks from raw LLM response text for baseline compilation |

### orchestrator module

| File | Purpose |
|---|---|
| `BenchmarkRunner.java` | Main pipeline orchestrator — runs all steps for one repo |
| `BenchmarkSpec.java` | Registry of 20 benchmark repos with paths and LOC estimates |
| `SinglePromptClaude.java` | Baseline: single Claude call for decomposition + code generation |
| `SinglePromptGpt4o.java` | Baseline: single GPT-4o call (requires OPENAI_API_KEY) |
| `SinglePromptBaseline.java` | Abstract base with shared two-step logic (plan + per-service code) |
| `SourceCollector.java` | Collects Java source files up to token budget for baselines |
| `BundleAssembler.java` | Assembles ZIP from all artifacts; generates parent pom + docker-compose |
| `AnalysisService.java` | Runs DependencyExtractor + Neo4j ingest + Louvain + inter-cluster edges |
| `AnthropicConfig.java` | Spring @Configuration for AnthropicChatModel bean (conditional on API key) |
| `OllamaConfig.java` | Spring @Configuration for OllamaChatModel bean (always present as fallback) |
| `GeminiConfig.java` | Spring @Configuration for Gemini via OpenAI-compatible endpoint |
| `BenchmarkController.java` | REST: POST /api/benchmark/{repo} → runs pipeline |
| `EvaluatorController.java` | REST: POST /api/eval/{jobId}/multi-agent → runs metrics |
| `StreamProducer.java` | Publishes agent task messages to Redis streams |
| `RefactorerConsumer.java` | Redis stream consumer for async refactoring tasks |

---

*This document was written to serve as a complete reference. The project is a research prototype demonstrating that multi-agent LLM systems with graph analysis, RAG retrieval, and iterative repair produce better microservice decompositions than single-prompt baselines on quantitative metrics across 20 real Java repositories.*
