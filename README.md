# Multi-Agent Legacy Code Modernizer

An AI-powered research platform that automatically decomposes Java monolithic applications into Spring Boot 3 microservices using a coordinated pipeline of five specialised LLM agents. Built as a flagship Master's application project, it is empirically benchmarked against single-prompt baselines across 20 open-source Java monoliths.

---

## Research hypothesis

> *Specialised multi-agent decomposition of Java-to-microservices migration tasks produces statistically significantly better outcomes than monolithic LLM prompting, as measured by compilation success rate, test coverage, API completeness, code quality score, inter-service coupling, and LCOM4 cohesion.*

Significance is tested with the Wilcoxon signed-rank test (paired, non-parametric) against two single-prompt baselines — GPT-4o and Claude Sonnet 4.6.

---

## What it does

Point it at a Java source tree. It:

1. **Parses** the codebase with JavaParser — every class, method, and call edge goes into Neo4j
2. **Clusters** the dependency graph with Louvain community detection to find natural service groupings
3. **Names** the clusters as DDD bounded contexts using the Architect LLM agent
4. **Extracts** shared utility classes used across boundaries into a `{repo}-commons` library (DD2)
5. **Generates** a complete Spring Boot 3 microservice per context — entity, DTO, repository, service, controller, pom.xml
6. **Repairs** any compilation errors by feeding `mvn compile` output back to the LLM (up to 3 iterations)
7. **Tests** — JUnit 5 unit tests and MockMvc integration tests per service
8. **Documents** — OpenAPI 3.0.3 YAML spec, Architecture Decision Record (MADR), migration runbook
9. **Bundles** everything into a downloadable ZIP with a root multi-module pom.xml and docker-compose.yml
10. **Evaluates** output against six metrics and compares to single-prompt baselines
11. **Analyses** results statistically across 20 benchmark repos with Wilcoxon tests and effect-size reports

---

## Architecture

```
Input: Java source tree
        │
        ▼
┌────────────────────────────────────────────────────────────────┐
│  Phase 1 — Static Analysis                                     │
│  JavaParser → Neo4j  (Class nodes, CALLS / EXTENDS / IMPL edges)│
│  Python Louvain clustering → service boundary seed groups      │
└──────────────────────────────┬─────────────────────────────────┘
                               │
                               ▼
┌────────────────────────────────────────────────────────────────┐
│  Phase 2 — Orchestration & Agents                              │
│  Job state machine   PENDING→ANALYZING→PLANNING→GENERATING→DONE│
│  Redis Streams       async agent task queue                    │
│  ArchitectAgent      LLM → DDD service boundaries              │
│  DD2 SharedLibraryDetector → {repo}-commons boundary          │
│  RefactorerAgent     LLM → per-class modernisation             │
│  RAG (Qdrant)        CodeBERT embeddings for per-agent context │
└──────────────────────────────┬─────────────────────────────────┘
                               │
                               ▼
┌────────────────────────────────────────────────────────────────┐
│  Phase 3 — Code Generation                                     │
│  ServiceGeneratorAgent  → 7 files per microservice             │
│  CompilationRepairService → feed errors back to LLM, retry ×3 │
│  TestWriterAgent        → JUnit 5 + MockMvc tests              │
│  DocGenAgent            → OpenAPI + ADR + Runbook              │
│  BundleAssembler        → ZIP download (commons-first order)   │
└──────────────────────────────┬─────────────────────────────────┘
                               │
                               ▼
┌────────────────────────────────────────────────────────────────┐
│  Phase 4 — Evaluation & Analysis                               │
│  CompilationMetric        mvn compile — real pass/fail         │
│  CoverageMetric           JaCoCo line coverage                 │
│  ApiCompletenessMetric    method-name set overlap              │
│  LlmJudgeMetric           cross-model judge (Claude ↔ GPT-4o) │
│  CouplingMetric           inter-service CALLS ratio (Neo4j)    │
│  CohesionMetric           LCOM4 via JavaParser + Union-Find    │
│  BaselineCodeExtractor    real mvn compile on extracted code   │
│  Wilcoxon tests           multi-agent vs both baselines        │
└────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌────────────────────────────────────────────────────────────────┐
│  DD3 — React Dashboard  (localhost:5173 / served from :8080)   │
│  JobList, JobDetail, ServiceCard, CodeViewer, MetricsPanel     │
│  SharedLibraryBadge, PhaseBar, StatusBadge                     │
└────────────────────────────────────────────────────────────────┘
```

### Infrastructure

| Service | Version | Purpose |
|---|---|---|
| PostgreSQL | 16 | Job state, artifacts, service boundaries, agent tasks, eval metrics |
| Redis | 7 | Async agent task queue (Redis Streams) |
| Neo4j | 5.19 | Code dependency graph — Class nodes + CALLS / EXTENDS / IMPLEMENTS edges |
| Qdrant | 1.9.4 | Vector store — CodeBERT embeddings for RAG retrieval |

### Maven modules

| Module | Responsibility |
|---|---|
| `core` | JPA entities, repositories, Neo4j ingestion, AST visitors, state machine, shared-library detection |
| `agents` | All LLM agents: Architect, Refactorer, ServiceGenerator, CompilationRepair, TestWriter, DocGen |
| `rag` | Java RAG retriever + Python indexing pipeline (CodeBERT → Qdrant) |
| `orchestrator` | Spring Boot entry point, REST API, Redis stream consumers, benchmark runner, bundle assembler |
| `evaluator` | Six quality metrics, EvalMetric entity, EvaluatorService, cross-model judge |

---

## What has been built

### Phase 1 — Static Analysis Foundation

- **JavaParser AST visitors** (`ClassVisitor`, `MethodVisitor`, `CallGraphVisitor`, `ImportVisitor`) — extract every class node, method signature, and inter-class call edge from the source tree
- **Neo4j ingestion** (`GraphIngester`) — writes the call graph as `(:Class)-[:CALLS]->(:Class)` nodes with `fqn`, `pkg`, `loc` properties; uniqueness constraint on `fqn`
- **Louvain clustering** (`louvain_cluster.py`) — runs community detection on the exported adjacency list to group tightly-coupled classes into candidate service clusters
- **`POST /api/jobs/{id}/analyze`** — triggers parse → Neo4j ingest → Louvain cluster

### Phase 2.1 — Database Schema (10 Flyway migrations)

| Migration | Table | Purpose |
|---|---|---|
| V1 | `migration_jobs` | One row per modernization run — status, source directory, metadata JSONB |
| V2 | `service_boundaries` | Named DDD bounded contexts — class lists, description, rationale |
| V3 | `agent_tasks` | Audit log of every LLM call — type, status, tokens, input/output |
| V4 | `artifacts` | Every generated file — content as TEXT, file_path, artifact_type |
| V5 | `eval_metrics` | Quality scores — metric_name, value, system_id, baseline, judge_model |
| V6 | `service_boundaries` | Added rationale column |
| V7 | `eval_metrics` | Added system_id column |
| V8 | `eval_metrics` | Added judge_model for cross-model attribution |
| V9 | `agent_tasks` | Added repair-tracking columns (first_attempt_compiled, repair_attempts) |
| V10 | `shared_classes` | DD2 — extracted commons classes, service_count, referencing_services |

### Phase 2.2 — Job Lifecycle State Machine

- **`JobStateMachine`** enforces: `PENDING → ANALYZING → PLANNING → GENERATING → DONE`; `FAILED` reachable from any non-terminal state; `FAILED → PENDING` for retries
- **`JobController`**: `POST /api/jobs`, `GET /api/jobs`, `GET /api/jobs/{id}/status`, `/advance`, `/retry`

### Phase 2.3 — Redis Streams Task Queue

- **`StreamProducer`** publishes `AgentTaskMessage` payloads onto named Redis Streams
- **`AbstractStreamConsumer`** — base class for all consumers; handles consumer-group `XREADGROUP` polling, `XACK` on success, dead-letter tracking on failure
- **Stream keys**: `agent:refactorer`, `agent:test_writer`, `agent:doc_generator`

### Phase 2.4 — Architect Agent + RAG

- **`ArchitectAgent`** receives the Louvain cluster map and calls the LLM to apply DDD bounded-context principles, naming each cluster as a deployable service
- Returns `ServiceBoundary` entities saved to PostgreSQL (name, class list, description, rationale)
- **Python RAG pipeline**: `method_chunker.py` → `embedder.py` (CodeBERT) → `qdrant_indexer.py`
- **`RagRetriever`** — queries Qdrant for top-k semantically relevant method chunks; used by all generation agents

### Phase 2.5 — Refactorer Agent

- **`RefactorerAgent`** transforms individual legacy classes — modernises JPA annotations, migrates `javax.*` → `jakarta.*`, enforces constructor injection, removes field `@Autowired`

### Phase 3.1 — Service Generator Agent

- **`ServiceGeneratorAgent`** takes a `ServiceBoundary` + RAG context and generates a complete Spring Boot 3 microservice via LLM
- Output: `<file><path>…</path><content>…</content></file>` XML blocks → parsed → 7 files per service (pom.xml, Application, entity, DTO, repository, service, controller)
- Detects `-commons` boundaries and switches to a plain Java library pom.xml (no Spring Boot parent)
- All files saved as `Artifact(SERVICE_CODE)` in PostgreSQL

### Phase 3.2 — Compilation Repair Loop

- **`CompilationRepairService`** runs `mvn compile -q` on each generated service; on failure, extracts structured `CompilerError` records and appends them to a repair prompt sent back to the LLM
- Up to 3 repair iterations per service; tracks `first_attempt_compiled` and `repair_attempts` in `agent_tasks`
- **`RepairStats`** aggregates first-attempt rate, final rate, avg attempts, unrepaired count per job
- **`GET /api/jobs/{id}/repair-stats`** — returns repair loop statistics for the dashboard

### Phase 3.3 — Test Writer Agent

- **`TestWriterAgent`** reads generated `SERVICE_CODE` artifacts and produces JUnit 5 unit tests (service layer, Mockito) and MockMvc integration tests (controller layer)
- Output saved as `Artifact(TEST_CODE)`

### Phase 3.4 — Doc Gen Agent

- **`DocGenAgent`** makes a single structured JSON LLM call returning three documents:
  - **OpenAPI 3.0.3 YAML** — all 5 CRUD endpoints, request/response schemas
  - **Architecture Decision Record** — MADR format with context, decision, consequences, alternatives
  - **Migration Runbook** — pre-conditions, deployment steps, verification `curl` commands, rollback
- Validated with `swagger-parser` v2.1.22 (zero parse errors required)

### Phase 3.5 — Bundle Assembler

- **`BundleAssembler`** reads all artifacts for a job, reconstructs the directory tree from `artifact.file_path`, and generates:
  - Root `pom.xml` — multi-module Maven wrapper, commons-first module ordering
  - `docker-compose.yml` — one service block per microservice (ports 8081+), shared postgres
  - `injectCommonsDependency()` — injects `<dependency>` on the commons module into all other services' pom.xml
- Packages everything into a ZIP via `ZipOutputStream`
- **`GET /api/jobs/{id}/bundle`** — returns `application/zip`

### Phase 4.1 — Benchmark Suite (20 repos)

20 open-source Java monoliths across a 100× LOC range:

| Repo | Approx LOC | Domain |
|---|---|---|
| spring-petclinic | 5k | Canonical test case |
| HikariCP | 15k | Connection pool |
| retrofit | 15k | HTTP client |
| caffeine | 20k | Caching library |
| jhipster-sample-app | 20k | Standard enterprise app |
| resilience4j | 30k | Fault tolerance |
| gson | 30k | JSON serialisation |
| jforum3 | 40k | Forum application |
| RxJava | 50k | Reactive streams |
| okhttp | 50k | HTTP client |
| zxing | 60k | Barcode processing |
| micrometer | 60k | Observability |
| BroadleafCommerce | 80k | E-commerce monolith |
| mybatis-3 | 80k | SQL mapper framework |
| openl-tablets | 100k | Business rules engine |
| flyway | 100k | Database migrations |
| Activiti | 150k | BPM engine |
| openmrs-core | 200k | Healthcare records |
| guava | 200k | Core Java utilities |
| dbeaver | 500k | IDE (sparse checkout) |

- **`BenchmarkRunner`** — runs the full 5-step pipeline per repo (analysis → architect → shared-lib detection → generation+repair → bundle) and writes `results/{repo}/multi-agent/`
- **`scripts/run_benchmarks.py`** — batch runner over all 20 repos via REST API

### Phase 4.2 — Single-Prompt Baselines

Two baselines using the **same single prompt** with no RAG, no multi-turn dialogue, no structured output enforcement:

- **`SinglePromptGpt4o`** — GPT-4o, 120k token source budget
- **`SinglePromptClaude`** — Claude Sonnet 4.6, 190k token budget
- **`SourceCollector`** — lexicographic file walk, accumulates until the char budget is hit
- Each run writes `response_raw.txt`, `response.json`, `metadata.json` to `results/{repo}/{system}/`

### Phase 4.3 — Metric Computation

Six metrics computed for the multi-agent system and both baselines:

| Metric | Description | Direction |
|---|---|---|
| **Compilation** | `mvn compile -q` per generated service — % success | ↑ higher is better |
| **Coverage** | JaCoCo LINE counter from `mvn test` → `jacoco.xml` | ↑ higher is better |
| **API completeness** | JavaParser public method name overlap: original ∩ generated / original | ↑ higher is better |
| **LLM judge** | Cross-model score on 5 dimensions (1–10 each) → mean | ↑ higher is better |
| **Inter-service coupling** | Cross-boundary CALLS / total CALLS in Neo4j graph | ↓ lower is better |
| **LCOM4 cohesion** | Avg connected components in method-field graph (avg + perfect % reported) | ↓ lower is better |

All results persisted to `eval_metrics` (`system_id` distinguishes multi-agent from baselines).

### Phase 4.4 — Statistical Analysis (`scripts/analyze_results.py`)

- **Data sources**: PostgreSQL (`--db-url`), CSV export (`--csv`), or synthetic data (auto-fallback)
- **Descriptive statistics**: mean ± std per metric × system across all 20 repos
- **Wilcoxon signed-rank test** (`scipy.stats.wilcoxon`, two-sided, α=0.05) per metric vs each baseline
- **Power analysis**: computed at n=10 and n=20 for small/medium/large Cohen's d
- **Repair analysis table**: first-attempt vs post-repair rate per repo with Δ improvement
- **Cohesion/coupling section**: per-system summary + Pearson r (avg_lcom4 vs compilation_rate)
- **Outputs** to `results/analysis/`:
  - `analysis_report.md` — full Markdown report
  - `analysis_report.tex` — LaTeX booktabs table for the paper
  - `summary_stats.json` / `wilcoxon_tests.json`
  - `plots/` — per-metric boxplots, per-repo heatmap, LOC scatter with regression line

### Phase 4.5 — Honest Baseline Metrics (BaselineCodeExtractor)

Before this phase, baseline compilation and coverage were hardcoded to 0.0. The extractor makes them real:

- **`BaselineCodeExtractor`** — parses every ` ```java ``` ` block from `response_raw.txt`, infers correct `package/ClassName.java` paths, writes files to a temp directory with a generated `pom.xml`, and runs `mvn compile`
- Path traversal guard prevents directory escape from the temp root
- Compilation rate = successful classes / total extracted classes
- Used by `CompilationMetric.evaluateBaseline()` and `CoverageMetric.evaluateBaseline()`

### Phase 4.6 — Cross-Model LLM Judge

Prevents a model from scoring its own output:

- Multi-agent output (Claude-generated) → **GPT-4o judges**
- GPT-4o baseline output → **Claude judges**
- Claude baseline output → **GPT-4o judges**
- **`CrossModelJudge`** routes each `(artifacts, systemId)` pair to the opposing model
- Judge model ID stored in `eval_metrics.judge_model` for full attribution
- Five scoring dimensions: correctness, readability, idiomaticity, completeness, DRY adherence

### Phase 4.7 — Compilation Repair Loop

Biggest single metric improvement. When `mvn compile` fails on a generated service:

1. Capture all `error:` lines from Maven output
2. Parse into structured `CompilerError` records (file, line, message)
3. Append errors to the original generation prompt as a repair request
4. Re-call the LLM, extract updated files, overwrite artifacts in DB
5. Repeat up to 3 times; give up and mark unrepaired if still failing

Tracked per service in `agent_tasks.first_attempt_compiled` and `repair_attempts`. The `RepairStatsController` exposes aggregate rates at `GET /api/jobs/{id}/repair-stats`.

### DD2 — Shared Library Extraction

Avoids duplicating utility classes across multiple generated microservices:

- **`SharedLibraryAnalyzer`** — tags Class nodes in Neo4j with their `serviceBoundary` property, then runs a Cypher query to find classes referenced by callers from ≥ `minServiceCount` (default 2) distinct service boundaries
- **`SharedLibraryDetector`** — orchestrates detection, strips shared classes from original `ServiceBoundary` rows, creates a `{repo}-commons` boundary, persists to `shared_classes` table
- **Pipeline integration** — `BenchmarkRunner` inserts DD2 between `ArchitectAgent` and `ServiceGeneratorAgent`; commons boundary is generated with a plain Java library pom.xml (no Spring Boot parent)
- **`BundleAssembler`** — puts the commons module first in the root `<modules>` list (required by Maven build order) and injects a `<dependency>` on commons into every other service's pom.xml
- **`MetricName.SHARED_CLASS_DUPLICATION_RATE`** — fraction of total classes extracted into commons; persisted for all runs
- **`GET /api/jobs/{id}/shared-classes`** — returns the `SharedLibraryPlan` JSON for the dashboard badge

### DD3 — React Migration Dashboard

A full Vite + React + TypeScript + Tailwind frontend served at `localhost:5173` (dev) with a Vite proxy to `localhost:8080`:

**Pages**
- **`JobList`** — sortable table of all migration jobs, status badges, "New Job" form (name + source directory path), 3-second polling interval for live status
- **`JobDetail`** — breadcrumb + job header with retry/download buttons, live-polling phase bar, stats row, shared library badge, commons module section, service boundary cards, evaluation metrics panel

**Components**
- **`PhaseBar`** — colour-coded 5-step progress bar (Pending → Analyzing → Planning → Generating → Done / Failed)
- **`StatusBadge`** — per-status colour chip
- **`ServiceCard`** — collapsible card per service boundary: class pill list, rationale text, generated file tree (pom.xml first), "View code" toggle → `CodeViewer`
- **`CodeViewer`** — tabbed syntax-highlighted viewer (Prism.js) with tabs: pom.xml, Application, Entity, DTO, Repository, Service, Controller, Tests; file-path header with line count
- **`SharedLibraryBadge`** — shows commons module name, shared class count, duplications avoided, duplication rate
- **`MetricsPanel`** — Recharts radar chart comparing multi-agent vs baselines across all quality metrics; bar chart showing first-attempt vs post-repair compilation rates

**Backend additions for the dashboard**
- **`BoundaryController`** — `GET /api/jobs/{id}/boundaries` (service boundary list with class counts) and `GET /api/jobs/{id}/artifacts/{serviceName}` (generated files sorted pom.xml-first)

### DD4 — LCOM4 Cohesion + Inter-Service Coupling Metrics

Two publishable software-engineering metrics that move evaluation beyond "does it compile":

**`CouplingMetric`** (`INTER_SERVICE_COUPLING`, lower is better)
- Multi-agent: re-tags Class nodes with service boundaries in Neo4j, counts cross-boundary CALLS edges, clears tags in `finally` block. Score = cross_calls / total_calls (0.0–1.0)
- Baseline: parses Java blocks from raw response, maps classes to services via `response.json`, counts cross-service import references

**`CohesionMetric`** (`AVG_LCOM4` + `PERFECT_COHESION_PCT`)
- LCOM4 algorithm: extract field names → for each method, collect referenced fields → build adjacency graph (two methods connected when they share ≥1 field) → count connected components via Union-Find
- LCOM4 = 1 is perfectly cohesive; > 1 means the class should be split further
- Multi-agent: runs on `SERVICE_CODE` artifacts; baseline: runs on ` ```java ``` ` blocks
- Reports avg LCOM4 and fraction of classes with LCOM4 = 1
- `CohesionMetricTest`: 14 unit tests covering known-fixture LCOM4 values, evaluate paths, and Union-Find internals

---

## REST API

```
# Job lifecycle
POST   /api/jobs                                   Create migration job
GET    /api/jobs                                   List all jobs
GET    /api/jobs/{id}/status                       Job status + metadata
POST   /api/jobs/{id}/advance                      Advance state (admin)
POST   /api/jobs/{id}/retry                        Reset FAILED → PENDING

# Analysis pipeline
POST   /api/jobs/{id}/analyze                      Phase 1: parse → Neo4j → Louvain
POST   /api/jobs/{id}/architect                    Phase 2: LLM → service boundaries

# Code generation (per boundary)
POST   /api/jobs/{id}/generate-service/{bid}       Phase 3: LLM → microservice + repair
POST   /api/jobs/{id}/write-tests/{bid}            Phase 3: LLM → JUnit 5 + MockMvc
POST   /api/jobs/{id}/generate-docs/{bid}          Phase 3: LLM → OpenAPI + ADR + Runbook

# Bundle + download
GET    /api/jobs/{id}/bundle                       Download ZIP archive

# Dashboard data
GET    /api/jobs/{id}/boundaries                   Service boundaries with class counts
GET    /api/jobs/{id}/artifacts/{serviceName}      Generated files for one service
GET    /api/jobs/{id}/repair-stats                 Compilation repair loop statistics
GET    /api/jobs/{id}/shared-classes               Shared library extraction plan

# RAG
POST   /api/rag/{id}/index                         Index source files into Qdrant

# Benchmark
GET    /api/benchmark                              List configured repos
POST   /api/benchmark/{repoName}                   Run full pipeline on one repo
POST   /api/benchmark/all                          Run all 20 repos

# Baselines
POST   /api/baseline/{repoName}                    Run both baselines
POST   /api/baseline/{repoName}/gpt4o              Run GPT-4o baseline only
POST   /api/baseline/{repoName}/claude             Run Claude baseline only
POST   /api/baseline/all                           Run all repos

# Evaluation
POST   /api/eval/{jobId}/multi-agent               Evaluate multi-agent run (all 6 metrics)
POST   /api/eval/{jobId}/baseline/{systemId}       Evaluate one baseline
POST   /api/eval/{jobId}/all                       Evaluate all systems for job
POST   /api/eval/all                               Evaluate all repos
GET    /api/eval/{jobId}                           Fetch stored metrics for dashboard
```

---

## Metrics reference

| Metric name | Enum | Direction | Description |
|---|---|---|---|
| Compilation rate | `COMPILATION_SUCCESS` | ↑ | % services where `mvn compile` exits 0 |
| First-attempt rate | `COMPILATION_FIRST_ATTEMPT` | ↑ | % services that compiled without any repair |
| Post-repair rate | `COMPILATION_POST_REPAIR` | ↑ | % services that compiled after repair loop |
| Test coverage | `COVERAGE` | ↑ | JaCoCo LINE covered / (covered + missed) |
| API completeness | `API_COMPLETENESS` | ↑ | Public method name overlap vs original |
| LLM judge score | `LLM_JUDGE_SCORE` | ↑ | Cross-model 5-dimension score (1–10) |
| Shared class rate | `SHARED_CLASS_DUPLICATION_RATE` | ↑ | Fraction of classes extracted into commons |
| Inter-service coupling | `INTER_SERVICE_COUPLING` | ↓ | Cross-boundary CALLS / total CALLS |
| Avg LCOM4 | `AVG_LCOM4` | ↓ | Avg connected components per class (1.0 = ideal) |
| Perfect cohesion % | `PERFECT_COHESION_PCT` | ↑ | Fraction of classes with LCOM4 = 1 |

---

## Test suite

| Module | Unit tests |
|---|---|
| core | `DependencyExtractorTest`, `JobStateMachineTest`, `SharedLibraryAnalyzerTest` |
| agents | `ArchitectAgentTest`, `ServiceGeneratorAgentTest`, `TestWriterAgentTest`, `DocGenAgentTest`, `RefactorerAgentTest` |
| orchestrator | `BundleEndToEndTest` (seeds artifacts → downloads ZIP → runs `mvn compile`) |
| evaluator | `MetricTest` (compilation, coverage, API completeness, LLM judge helpers), `BaselineCodeExtractorTest`, `CohesionMetricTest` (14 tests — LCOM4 algorithm fixtures, UnionFind), `CrossModelJudgeTest` |

Integration tests (requiring live Postgres, Neo4j, Redis, or an LLM key) auto-skip via `assumeTrue(portOpen(...))` or `@EnabledIfEnvironmentVariable` — the full unit suite runs offline.

---

## Quick start

**Prerequisites:** Java 21, Maven 3.9+, Docker, Python 3.11+

```bash
# 1. Start all infrastructure
docker compose up -d

# 2. Build all modules
mvn install -q

# 3. Run (set ANTHROPIC_API_KEY for Claude; falls back to Ollama otherwise)
export ANTHROPIC_API_KEY=sk-ant-...
cd orchestrator && mvn spring-boot:run

# 4. Open the dashboard
open http://localhost:5173        # Vite dev server (npm run dev inside frontend/)
# or use the API directly via requests.http
```

**Run the full benchmark pipeline:**
```bash
# Trigger multi-agent pipeline for all 20 repos
python3 scripts/run_benchmarks.py --base-url http://localhost:8080

# Run baselines
curl -X POST http://localhost:8080/api/baseline/all

# Run evaluation (all 6 metrics, all systems)
curl -X POST http://localhost:8080/api/eval/all

# Statistical analysis (reads from DB)
python3 scripts/analyze_results.py \
  --db-url postgresql://postgres:postgres@localhost/lcm

# Or use synthetic data (no DB needed)
python3 scripts/analyze_results.py --synthetic
# → results/analysis/analysis_report.md + .tex + plots/
```

**Start the frontend (development):**
```bash
cd frontend
npm install
npm run dev          # http://localhost:5173 — proxies /api to localhost:8080
```

All HTTP examples are in `requests.http` (IntelliJ HTTP Client format).

---

## Project structure

```
legacy-code-modernizer/
├── core/                       JPA entities, repositories, Neo4j ingestion,
│                               AST visitors, state machine, shared-library detection
├── agents/                     LLM agents: Architect, Refactorer, ServiceGenerator,
│                               CompilationRepair, TestWriter, DocGen
├── rag/                        CodeBERT RAG indexer (Java) + Python indexing pipeline
├── orchestrator/               Spring Boot entry point, REST API (14 controllers),
│                               Redis stream consumers, BenchmarkRunner, BundleAssembler
├── evaluator/                  6 metric classes, EvalMetric entity,
│                               EvaluatorService, CrossModelJudge, BaselineCodeExtractor
├── frontend/                   Vite + React + TypeScript + Tailwind dashboard
│   └── src/
│       ├── pages/              JobList, JobDetail
│       ├── components/         ServiceCard, CodeViewer, MetricsPanel,
│       │                       SharedLibraryBadge, PhaseBar, StatusBadge
│       ├── api.ts              All REST calls (axios + react-query)
│       └── types.ts            TypeScript interfaces
├── benchmarks/                 20 cloned repos (gitignored, --depth=1)
├── results/                    Pipeline outputs per repo per system
│   ├── {repo}/
│   │   ├── multi-agent/        bundle.zip, metrics.json
│   │   ├── single-prompt-claude/  response_raw.txt, response.json
│   │   └── single-prompt-gpt4o/
│   └── analysis/               summary_stats.json, wilcoxon_tests.json,
│                                analysis_report.md, analysis_report.tex, plots/
├── scripts/                    Python: Louvain, CodeBERT indexing,
│                               benchmark runner, statistical analysis
├── docker-compose.yml          Postgres 16, Redis 7, Neo4j 5, Qdrant 1.9
└── requests.http               All API examples (IntelliJ HTTP Client)
```

---

## Key design decisions

**Why Louvain before LLM?** Graph clustering finds natural cohesion boundaries from *actual call patterns*, removing reliance on LLM intuition for structural decomposition. The LLM then only needs to *name* pre-computed boundaries — a much narrower task.

**Why method-level RAG?** File-level chunking loses method context and wastes tokens on boilerplate. CodeBERT at method granularity gives each agent exactly the semantically relevant code it needs — the key to handling 50k–500k LOC monoliths within a context window.

**Why Redis Streams?** At-least-once delivery with consumer groups makes each agent independently scalable and fault-tolerant. A failed agent task is retried by the consumer group without any orchestrator change.

**Why a compilation repair loop?** First-attempt compilation failures are systematic — missing imports, wrong class names, incorrect Spring annotations. Three targeted LLM repair iterations recover the majority of failing services without any human intervention, and the gain is measurable (first-attempt rate vs final rate is reported per repo).

**Why a commons module (DD2)?** Without shared-class extraction, utility classes that are referenced across multiple service boundaries get copy-pasted into each service. The commons module eliminates this duplication at the Maven dependency level, producing a cleaner `SHARED_CLASS_DUPLICATION_RATE` metric and a legally deployable artifact rather than duplicated sources.

**Why LCOM4 and coupling (DD4)?** Method-name overlap (API completeness) is a weak proxy that a reviewer would dismiss. LCOM4 and inter-service coupling are standard metrics in the software engineering literature — using them makes the evaluation publishable and lets us claim "our decomposition produces *lower coupling and higher cohesion* than baselines", not just "it compiles more often."

**Why cross-model judging?** A model judging its own output has a systematic self-preference bias. Routing Claude-generated output to GPT-4o and vice versa removes that bias and attributes the judge model in `eval_metrics.judge_model` for full reproducibility.
