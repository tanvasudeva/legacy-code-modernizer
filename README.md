# Multi-Agent Legacy Code Modernizer

An AI-powered research platform that automatically decomposes Java monolithic applications into Spring Boot 3 microservices using a coordinated pipeline of five specialized LLM agents. Built as a flagship Master's application project, it is empirically benchmarked against single-prompt baselines across 10 open-source Java monoliths.

---

## Research hypothesis

> *Specialized multi-agent decomposition of Java-to-microservices migration tasks produces statistically significantly better outcomes than monolithic LLM prompting, as measured by compilation success rate, test coverage, API completeness, and code quality score.*

Significance is tested with the Wilcoxon signed-rank test (paired, non-parametric) against two single-prompt baselines — GPT-4o and Claude claude-sonnet-4-6.

---

## What it does

Point it at a Java source tree. It:

1. **Parses** the codebase with JavaParser — every class, method, and call edge goes into Neo4j
2. **Clusters** the dependency graph with Louvain community detection to find natural service groupings
3. **Names** the clusters as DDD bounded contexts using the Architect LLM agent
4. **Generates** a complete Spring Boot 3 microservice per context — entity, DTO, repository, service, controller, pom.xml
5. **Tests** — JUnit 5 unit tests and MockMvc integration tests per service
6. **Documents** — OpenAPI 3.0.3 YAML spec, Architecture Decision Record (MADR), migration runbook
7. **Bundles** everything into a downloadable ZIP with a root multi-module pom.xml and docker-compose.yml
8. **Evaluates** the output against four metrics and compares to single-prompt baselines
9. **Analyses** results statistically across all 10 benchmark repos

---

## Architecture

```
Input: Java source tree
        │
        ▼
┌────────────────────────────────────────────────────┐
│  Phase 1 — Static Analysis                         │
│  JavaParser → Neo4j (class nodes, call edges)      │
│  Python Louvain clustering → service boundary hints │
└─────────────────────┬──────────────────────────────┘
                      │
        ▼
┌────────────────────────────────────────────────────┐
│  Phase 2 — Orchestration & Agents                  │
│  Job state machine  PENDING→ANALYZING→PLANNING     │
│                     →GENERATING→DONE               │
│  Redis Streams      async agent task queue         │
│  ArchitectAgent     LLM → DDD service boundaries   │
│  RefactorerAgent    LLM → per-class transformation │
│  RAG (Qdrant)       CodeBERT embeddings for context│
└─────────────────────┬──────────────────────────────┘
                      │
        ▼
┌────────────────────────────────────────────────────┐
│  Phase 3 — Code Generation                         │
│  ServiceGeneratorAgent → 7 files per microservice  │
│  TestWriterAgent       → JUnit 5 + MockMvc tests   │
│  DocGenAgent           → OpenAPI + ADR + Runbook   │
│  BundleAssembler       → ZIP download              │
└─────────────────────┬──────────────────────────────┘
                      │
        ▼
┌────────────────────────────────────────────────────┐
│  Phase 4 — Evaluation & Analysis                   │
│  CompilationMetric    mvn compile per service      │
│  CoverageMetric       JaCoCo line coverage         │
│  ApiCompletenessMetric method-name set overlap     │
│  LlmJudgeMetric       Claude 5-dimension scorer    │
│  Wilcoxon tests       multi-agent vs baselines     │
└────────────────────────────────────────────────────┘
```

### Infrastructure

| Service | Purpose |
|---|---|
| PostgreSQL 16 | Job state, artifacts, service boundaries, agent tasks, eval metrics |
| Redis 7 | Async agent task queue (Redis Streams) |
| Neo4j 5 | Code dependency graph (class nodes + call edges) |
| Qdrant 1.9 | Vector store — CodeBERT embeddings for RAG retrieval |

### Maven modules

| Module | Production files | Responsibility |
|---|---|---|
| `core` | 23 | JPA entities, repositories, state machine, AST visitors |
| `agents` | 11 | All LLM-powered agents (LangChain4j + Claude / Ollama) |
| `rag` | 3 | Java RAG retriever + Python indexing pipeline |
| `orchestrator` | 37 | Spring Boot entry point, REST API, stream consumers, benchmark runners |
| `evaluator` | 8 | Quality metrics, EvalMetric entity, EvaluatorService |

---

## What has been built

### Phase 1 — Static Analysis Foundation

- **JavaParser AST visitors** (`ClassVisitor`, `MethodVisitor`, `CallGraphVisitor`, `ImportVisitor`) — extract every class node, method signature, and inter-class call edge from the source tree
- **Neo4j ingestion** — `GraphIngester` writes the call graph as `(:Class)-[:CALLS]->(:Class)` nodes
- **Louvain clustering** — `louvain_cluster.py` runs community detection on the exported adjacency list, grouping tightly-coupled classes into candidate service clusters
- **REST API** — `POST /api/jobs/{id}/analyze` triggers parse → ingest → cluster and returns the cluster map

### Phase 2.1 — Database Schema

Seven Flyway migrations define the full persistence schema:

| Table | Purpose |
|---|---|
| `migration_jobs` | One row per modernization run — status, source directory, metadata JSONB |
| `service_boundaries` | Named DDD bounded contexts with class lists and LLM rationale |
| `agent_tasks` | Audit log of every LLM call — type, status, tokens, input/output JSONB |
| `artifacts` | Every generated file — content as TEXT, `file_path`, `artifact_type` |
| `eval_metrics` | Quality scores — `metric_name`, `metric_value`, `system_id`, `baseline` |

### Phase 2.2 — Job Lifecycle

- **`JobStateMachine`** enforces: `PENDING → ANALYZING → PLANNING → GENERATING → DONE`, `FAILED` reachable from any non-terminal state, `FAILED → PENDING` for retries
- **`JobService`** — create, advance, fail, retry, findById
- **`JobController`** — `POST /api/jobs`, `GET /api/jobs/{id}/status`, `/advance`, `/retry`

### Phase 2.3 — Redis Streams Queue

- **`StreamProducer`** publishes `AgentTaskMessage` payloads onto named Redis Streams
- **`AbstractStreamConsumer`** — base class all agent consumers extend; handles consumer-group registration, `XREADGROUP` polling, `XACK` on success, dead-letter tracking on failure
- **Stream keys**: `agent:refactorer`, `agent:test_writer`, `agent:doc_generator`

### Phase 2.4 — Architect Agent

- **`ArchitectAgent`** calls the LLM with the Louvain cluster map and applies DDD bounded-context principles to name each cluster as a deployable service
- Returns `ServiceBoundary` entities saved to PostgreSQL (name, class list, description, rationale)
- **`POST /api/jobs/{id}/architect`**

### Phase 2.5 — Refactorer Agent + RAG

- **`RefactorerAgent`** transforms individual legacy classes — modernises JPA annotations, `javax.*` → `jakarta.*`, constructor injection, removes field `@Autowired`
- **Python RAG pipeline**: `method_chunker.py` → `embedder.py` (CodeBERT) → `qdrant_indexer.py` (per-job Qdrant collection)
- **`RagRetriever`** — queries Qdrant for top-k most semantically relevant method chunks given a natural-language query; used by all three generation agents

### Phase 3.1 — Service Generator Agent

- **`ServiceGeneratorAgent`** takes a `ServiceBoundary` + RAG context and generates a complete Spring Boot 3 microservice via LLM
- Output: `<file><path>…</path><content>…</content></file>` XML blocks → parsed → 7 files per service (pom.xml, Application, entity, DTO, repository, service, controller)
- All files saved as `Artifact(SERVICE_CODE)` in PostgreSQL
- **`POST /api/jobs/{id}/generate-service/{boundaryId}`**

### Phase 3.2 — Test Writer Agent

- **`TestWriterAgent`** reads generated `SERVICE_CODE` artifacts and produces:
  - JUnit 5 unit test for the service layer with Mockito
  - MockMvc integration test for the controller
- Output saved as `Artifact(TEST_CODE)`
- **`POST /api/jobs/{id}/write-tests/{boundaryId}`**

### Phase 3.3 — Doc Gen Agent

- **`DocGenAgent`** makes a single structured JSON LLM call returning three documents:
  - **OpenAPI 3.0.3 YAML** — all 5 CRUD endpoints, request/response schemas
  - **Architecture Decision Record** — MADR format with context, decision, consequences, alternatives
  - **Migration Runbook** — pre-conditions, deployment steps, verification `curl` commands, rollback
- Validated with `swagger-parser` v2.1.22 (zero parse errors required in integration test)
- **`POST /api/jobs/{id}/generate-docs/{boundaryId}`**

### Phase 3.4 — Bundle Assembler ★ E2E milestone

- **`BundleAssembler`** reads all artifacts for a job, reconstructs the directory tree from `artifact.file_path`, and generates:
  - Root `pom.xml` — multi-module Maven wrapper (Spring Boot 3.2.5 parent, one `<module>` per service)
  - `docker-compose.yml` — one service block per microservice (ports 8081+), shared `postgres:16-alpine`
- Packages everything into a ZIP via `ZipOutputStream`
- **`GET /api/jobs/{id}/bundle`** — returns `application/zip` with `Content-Disposition: attachment`
- **End-to-end test** (`BundleEndToEndTest`): seeds canonical owner-service artifacts → downloads bundle → unzips to temp dir → runs `mvn compile -q` → asserts exit code 0

### Phase 4.1 — Benchmark Setup

- **10 open-source Java monoliths** cloned into `benchmarks/` (gitignored, `--depth=1`):

  | Repo | Approx LOC | Domain |
  |---|---|---|
  | spring-petclinic | 5k | Canonical test case |
  | HikariCP | 15k | Connection pool |
  | jhipster-sample-app | 20k | Standard enterprise app |
  | jforum3 | 40k | Forum application |
  | zxing | 60k | Barcode processing |
  | BroadleafCommerce | 80k | E-commerce monolith |
  | openl-tablets | 100k | Business rules engine |
  | Activiti | 150k | BPM engine |
  | openmrs-core | 200k | Healthcare records |
  | dbeaver | 500k | IDE (sparse checkout) |

- **`BenchmarkRunner`** — Spring `@Component` running the full 5-step pipeline per repo and writing `results/{repo}/multi-agent/bundle.zip` + `metrics.json`
- **`BenchmarkController`** — `POST /api/benchmark/{repoName}`, `POST /api/benchmark/all`
- **`scripts/run_benchmarks.py`** — batch runner over all 10 repos via REST API

### Phase 4.2 — Single-Prompt Baselines

Two baseline systems using the **same single prompt** with no RAG, no multi-turn dialogue, no structured output enforcement:

- **`SinglePromptGpt4o`** — GPT-4o via `langchain4j-open-ai`, 120k token source budget (≈480k chars), requires `OPENAI_API_KEY`
- **`SinglePromptClaude`** — claude-sonnet-4-6 via `langchain4j-anthropic`, 190k token budget, requires `ANTHROPIC_API_KEY`
- **`SourceCollector`** — walks the benchmark source tree, sorts files lexicographically, accumulates until the char budget is hit
- Each run writes `response_raw.txt`, `response.json`, `metadata.json` to `results/{repo}/{system}/`
- **`BaselineController`** — `POST /api/baseline/{repo}`, `/gpt4o`, `/claude`, `/all`

### Phase 4.3 — Metric Computation

Four metrics computed for the multi-agent system and both baselines across all 10 repos:

| Metric | Multi-agent | Baselines |
|---|---|---|
| **Compilation** | Write SERVICE_CODE to temp dir → `mvn compile -q` per service → % success | Always 0.0 (no code generated) |
| **Coverage** | Inject JaCoCo plugin → `mvn test` → parse `jacoco.xml` LINE counter | Always 0.0 (no tests generated) |
| **API completeness** | JavaParser public method name overlap: original ∩ generated / original | Class simple-name overlap: original ∩ baseline classFqns / original |
| **LLM judge** | Claude scores generated code on 5 dimensions (1–10 each) → mean | Claude scores the service boundary plan quality on the same 5 dimensions |

All results persisted to the `eval_metrics` table (`system_id` distinguishes multi-agent from baselines).

- **`EvaluatorService`** — orchestrates all four metrics, persists to DB
- **`EvaluatorController`** — `POST /api/eval/{jobId}/multi-agent`, `/baseline/{systemId}`, `/all`

### Phase 4.4 — Statistical Analysis

- **`scripts/analyze_results.py`** — full statistical analysis pipeline
- **Data sources**: PostgreSQL (`--db-url`), CSV export (`--csv`), or synthetic data (auto-fallback for development)
- **Descriptive statistics**: mean ± std per metric × system across all 10 repos
- **Wilcoxon signed-rank test** (`scipy.stats.wilcoxon`, two-sided, α=0.05): multi-agent vs each baseline per metric; handles zero-difference and low-sample edge cases
- **Outputs** written to `results/analysis/`:
  - `analysis_report.md` — Markdown results table with ✓ significance markers + Discussion section
  - `analysis_report.tex` — LaTeX booktabs table ready to paste into the paper
  - `summary_stats.json` / `wilcoxon_tests.json` — raw numbers for programmatic use
  - `plots/` — per-metric boxplots + multi-agent per-repo heatmap (LOC-ordered)
- **Struggling-repo analysis**: Pearson r (log-LOC) per metric; named patterns for Discussion (large-repo degradation, unconventional build structures, over-splitting, coverage environment gap)

---

## Test coverage

| Module | Test files | `@Test` methods |
|---|---|---|
| core | 3 | 38 |
| agents | 10 | 103 |
| rag | 2 | 13 |
| orchestrator | 5 | 63 |
| evaluator | 1 | 17 |
| **Total** | **21** | **234** |

Integration tests (requiring Postgres, Neo4j, Redis, or an LLM) are auto-skipped via `assumeTrue(portOpen(...))` or `@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY")` — the full unit suite runs offline.

---

## REST API

```
# Job lifecycle
POST   /api/jobs                                   Create migration job
GET    /api/jobs                                   List all jobs
GET    /api/jobs/{id}/status                       Job status
POST   /api/jobs/{id}/advance                      Advance state (admin)
POST   /api/jobs/{id}/retry                        Reset FAILED → PENDING

# Analysis pipeline
POST   /api/jobs/{id}/analyze                      Phase 1: parse → Neo4j → cluster
POST   /api/jobs/{id}/architect                    Phase 2.4: LLM → service boundaries

# Code generation (per boundary)
POST   /api/jobs/{id}/generate-service/{bid}       Phase 3.1: LLM → microservice source
POST   /api/jobs/{id}/write-tests/{bid}            Phase 3.2: LLM → JUnit 5 + MockMvc
POST   /api/jobs/{id}/generate-docs/{bid}          Phase 3.3: LLM → OpenAPI + ADR + Runbook

# Bundle
GET    /api/jobs/{id}/bundle                       Phase 3.4: download ZIP

# RAG
POST   /api/rag/{id}/index                         Index source files into Qdrant

# Benchmark
GET    /api/benchmark                              List repos
POST   /api/benchmark/{repoName}                   Run multi-agent pipeline
POST   /api/benchmark/all                          Run all 10 repos

# Baselines
POST   /api/baseline/{repoName}                    Run both baselines
POST   /api/baseline/{repoName}/gpt4o              Run GPT-4o baseline only
POST   /api/baseline/{repoName}/claude             Run Claude baseline only
POST   /api/baseline/all                           Run all repos

# Evaluation
POST   /api/eval/{jobId}/multi-agent               Evaluate multi-agent run
POST   /api/eval/{jobId}/baseline/{systemId}       Evaluate one baseline
POST   /api/eval/{jobId}/all                       Evaluate all systems for job
POST   /api/eval/all                               Evaluate all repos
GET    /api/eval/{jobId}                           Fetch stored metrics
```

---

## Quick start

**Prerequisites:** Java 21, Maven 3.9+, Docker, Python 3.11+

```bash
# 1. Start all infrastructure
docker compose up -d

# 2. Build
mvn install -q

# 3. Run (set ANTHROPIC_API_KEY for Claude; falls back to Ollama otherwise)
export ANTHROPIC_API_KEY=sk-ant-...
cd orchestrator && mvn spring-boot:run
```

All HTTP examples are in `requests.http` (IntelliJ HTTP Client format).

**Run the full benchmark pipeline:**
```bash
# Trigger multi-agent pipeline for all 10 repos
python3 scripts/run_benchmarks.py --base-url http://localhost:8080

# Run baselines
curl -X POST http://localhost:8080/api/baseline/all

# Run evaluation
curl -X POST http://localhost:8080/api/eval/all

# Statistical analysis (reads from DB)
python3 scripts/analyze_results.py --db-url postgresql://postgres:postgres@localhost/lcm
```

**Run analysis with synthetic data (no DB needed):**
```bash
python3 scripts/analyze_results.py --synthetic
# Outputs: results/analysis/analysis_report.md + .tex + plots/
```

---

## Project structure

```
legacy-code-modernizer/
├── core/                       JPA entities, repositories, state machine, AST visitors
├── agents/                     LLM agents (Architect, Refactorer, ServiceGen, TestWriter, DocGen)
├── rag/                        CodeBERT RAG indexer and retriever
├── orchestrator/               Spring Boot app, REST API, benchmark runners, evaluator controller
├── evaluator/                  Metric classes, EvalMetric entity, EvaluatorService
├── benchmarks/                 10 cloned repos (gitignored, --depth=1)
├── results/                    Pipeline outputs per repo per system
│   ├── {repo}/
│   │   ├── multi-agent/        bundle.zip, metrics.json
│   │   ├── single-prompt-claude/  response_raw.txt, response.json, metadata.json
│   │   └── single-prompt-gpt4o/
│   └── analysis/               summary_stats.json, wilcoxon_tests.json,
│                                analysis_report.md, analysis_report.tex, plots/
├── scripts/                    Python: Louvain, CodeBERT indexing, benchmark runner, analysis
├── docker-compose.yml          Postgres, Redis, Neo4j, Qdrant
└── requests.http               All API examples (IntelliJ HTTP Client)
```

---

## Key design decisions

**Why Louvain before LLM?** Graph clustering finds natural cohesion boundaries from *actual call patterns*, removing reliance on LLM intuition for the structural decomposition. The LLM then only needs to *name* those pre-computed boundaries — a much narrower task.

**Why method-level RAG?** File-level chunking loses method context and wastes tokens on boilerplate. CodeBERT at method granularity gives each agent exactly the semantically relevant code it needs, solving the context-window problem for large monoliths (50k–500k LOC).

**Why Redis Streams?** At-least-once delivery with consumer groups makes each agent independently scalable and fault-tolerant. A failed agent task is retried by the same consumer group without any orchestrator change.

**Why PostgreSQL as state store?** Every intermediate output is persisted before the next step begins. Any agent failure can be retried from the exact failure point — the pipeline is fully resumable without re-running earlier phases.

**Why a fixed pom.xml template?** The `ServiceGeneratorAgent` system prompt enforces a specific pom.xml structure (Spring Boot 3.2.5 parent, `jakarta.*`, constructor injection). This trades flexibility for a high first-attempt compilation rate. Per-repo adaptation is a known improvement area for unconventional codebases (OSGi, BPM engines).
