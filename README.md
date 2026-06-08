# Legacy Code Modernizer

An AI-powered platform that takes a legacy Java monolith, analyses its structure, and automatically generates a set of deployable Spring Boot 3 microservices — complete with tests, OpenAPI docs, architecture decision records, and a ready-to-run Docker bundle.

---

## What it does

You point it at a Java source tree (e.g. Spring PetClinic). It:

1. **Parses and graphs** the codebase — every class, method, and call edge goes into Neo4j
2. **Clusters** the graph using Louvain community detection to find natural service boundaries
3. **Asks an LLM** (Claude / Ollama) to name those boundaries as DDD bounded contexts
4. **Generates** a full Spring Boot 3 microservice for each bounded context — entity, DTO, repository, service, controller, pom.xml
5. **Writes tests** — JUnit 5 unit tests and MockMvc integration tests for each generated service
6. **Writes docs** — OpenAPI 3.0.3 YAML spec, an Architecture Decision Record (MADR format), and a migration runbook
7. **Bundles everything** into a downloadable ZIP with a root multi-module pom.xml and docker-compose.yml, ready for `mvn compile`

The whole thing is driven through a REST API. Each phase is a separate HTTP call so you can inspect and intervene at every step.

---

## Architecture overview

```
Input: Java source tree
        │
        ▼
┌─────────────────────────────────────────────────────┐
│  Phase 1 — Static Analysis                          │
│  JavaParser → Neo4j (class nodes, call edges)       │
│  Python Louvain clustering → service boundary hints  │
└──────────────────────┬──────────────────────────────┘
                       │
        ▼
┌─────────────────────────────────────────────────────┐
│  Phase 2 — Orchestration Layer                      │
│  Job state machine  PENDING→ANALYZING→PLANNING      │
│                     →GENERATING→DONE                │
│  Redis Streams      async agent task queue          │
│  ArchitectAgent     LLM → named DDD boundaries      │
│  RefactorerAgent    LLM → per-class transformation  │
│  RAG (Qdrant)       CodeBERT embeddings for context │
└──────────────────────┬──────────────────────────────┘
                       │
        ▼
┌─────────────────────────────────────────────────────┐
│  Phase 3 — Code Generation                          │
│  ServiceGeneratorAgent  → 7 files per microservice  │
│  TestWriterAgent        → JUnit 5 + MockMvc tests   │
│  DocGenAgent            → OpenAPI + ADR + Runbook   │
│  BundleAssembler        → ZIP download              │
└──────────────────────┬──────────────────────────────┘
                       │
        ▼
┌─────────────────────────────────────────────────────┐
│  Phase 4 — Evaluation  (scaffolded, coming next)    │
│  Compilation check, test pass rate,                 │
│  AST similarity score, Halstead complexity          │
└─────────────────────────────────────────────────────┘
```

**Infrastructure** (all started with `docker compose up`):

| Service | Purpose |
|---|---|
| PostgreSQL 16 | Job state, artifacts, service boundaries, agent tasks, eval metrics |
| Redis 7 | Async agent task queue (Redis Streams) |
| Neo4j 5 | Code dependency graph (class nodes + call edges) |
| Qdrant 1.9 | Vector store for RAG — CodeBERT embeddings of method bodies |

**Maven modules:**

| Module | Responsibility |
|---|---|
| `core` | JPA entities, repositories, state machine, AST visitors |
| `orchestrator` | Spring Boot app, REST controllers, Redis stream consumers, bundle assembly |
| `agents` | All LLM-powered agents (LangChain4j + Claude / Ollama) |
| `rag` | Java RAG retriever + Python indexing scripts |
| `evaluator` | Quality scoring (scaffolded — Phase 4) |

---

## What has been built

### Phase 1 — Static Analysis Foundation
*Commit: `feat: Phase 1 foundation`*

- **JavaParser AST visitors** — `ClassVisitor`, `MethodVisitor`, `CallGraphVisitor`, `ImportVisitor` extract every class node, method signature, and inter-class call edge from the source tree
- **Neo4j ingestion** — `GraphIngester` writes the call graph as `(:Class)-[:CALLS]->(:Class)` nodes; `IngestionStats` reports totals
- **Louvain clustering** — `louvain_cluster.py` runs community detection on the call graph export, producing a cluster map that groups tightly-coupled classes together
- **Analysis REST API** — `POST /api/jobs/{id}/analyze` triggers the full parse → ingest → cluster pipeline and returns cluster counts and the class-to-cluster map

### Phase 2.1 — Database Schema
*Commit: `feat(db): Phase 2.1`*

Six Flyway migrations define the full persistence schema:

| Table | Purpose |
|---|---|
| `migration_jobs` | One row per modernization run — name, source dir, status, metadata (JSONB) |
| `service_boundaries` | Named DDD bounded contexts with class lists and LLM rationale |
| `agent_tasks` | Audit log of every LLM call — type, status, tokens used, input/output |
| `artifacts` | Every generated file — content stored as TEXT with file_path and artifact_type |
| `eval_metrics` | Quality scores per artifact (Phase 4) |

### Phase 2.2 — Job Lifecycle
*Commit: `feat(orchestrator): Phase 2.2`*

- **`JobStateMachine`** enforces valid transitions: `PENDING → ANALYZING → PLANNING → GENERATING → DONE`, with `FAILED` reachable from any non-terminal state and `FAILED → PENDING` for retries
- **`JobService`** — create, advance, fail, retry, findById
- **`JobController`** — `POST /api/jobs`, `GET /api/jobs/{id}/status`, `POST /api/jobs/{id}/advance`, `POST /api/jobs/{id}/retry`

### Phase 2.3 — Redis Streams Queue
*Commit: `feat(stream): Phase 2.3`*

- **`StreamProducer`** publishes `AgentTaskMessage` payloads onto named Redis Streams (one per agent type)
- **`AbstractStreamConsumer`** is the base class all agent consumers extend — it handles consumer-group registration, `XREADGROUP` polling, `XACK` on success, and dead-letter tracking on failure
- **`StreamKeys`** defines the canonical stream names: `agent:refactorer`, `agent:test_writer`, `agent:doc_generator`

### Phase 2.4 — Architect Agent
*Commit: `feat(agents): Phase 2.4`*

- **`ArchitectAgent`** calls the LLM with the full cluster map and class list from Phase 1 and asks it to name each cluster as a DDD bounded context with a domain description and rationale
- Returns `ServiceBoundary` entities saved to PostgreSQL; each boundary lists the original class FQNs that belong to it
- **`POST /api/jobs/{id}/architect`** triggers this and returns all named boundaries

### Phase 2.5 — Refactorer Agent + RAG
*Commits: `feat(agents): Phase 2.5`, `feat(rag): Phase 2.5`*

- **`RefactorerAgent`** transforms individual legacy classes — modernises JPA annotations, converts `javax.*` to `jakarta.*`, introduces constructor injection, removes field-level `@Autowired`. Each transformed class is saved as an `Artifact(TRANSFORMED)`.
- **Python RAG pipeline**:
  - `method_chunker.py` — walks the source tree and splits each method body into a CodeChunk
  - `embedder.py` — generates CodeBERT embeddings for every chunk
  - `qdrant_indexer.py` — upserts vectors into Qdrant, one collection per job
- **`RagIndexService`** / **`RagRetriever`** — Java-side wrappers that call the Python embedder and query Qdrant for the top-k most relevant snippets given a natural-language query. Used by later agents to give the LLM grounding in the original codebase.

### Phase 3.1 — Service Generator Agent
*Commit: `feat(agents): Phase 3.1`*

- **`ServiceGeneratorAgent`** takes a `ServiceBoundary` + RAG context snippets and asks the LLM to produce a complete, compilable Spring Boot 3 microservice
- Output format: the LLM returns `<file><path>…</path><content>…</content></file>` XML blocks; the agent parses them and saves each as an `Artifact(SERVICE_CODE)`
- Each service gets 7 files: `pom.xml`, `Application.java`, entity, DTO, repository, service, controller — all following strict compilation rules (Java 21, `jakarta.*`, constructor injection, `JpaRepository`)
- **`POST /api/jobs/{id}/generate-service/{boundaryId}`** — includes RAG context fetch, returns file-level summary

### Phase 3.2 — Test Writer Agent
*Commit: `feat(agents): Phase 3.2`*

- **`TestWriterAgent`** reads the generated `SERVICE_CODE` artifacts for a boundary and produces:
  - A **JUnit 5 unit test** for the service layer using Mockito — tests `findAll`, `findById`, `create`, `update`, `delete`
  - A **MockMvc integration test** for the controller layer — tests all 5 REST endpoints with mock service injection
- Output saved as `Artifact(TEST_CODE)`, following the same `<file>` block parsing approach
- **`POST /api/jobs/{id}/generate-tests/{boundaryId}`**

### Phase 3.3 — Doc Gen Agent
*Commit: `feat(agents): Phase 3.3`*

- **`DocGenAgent`** makes a single LLM call that returns structured JSON with three documents:
  - **OpenAPI 3.0.3 YAML** — full spec derived from the controller source (all 5 CRUD endpoints, request/response schemas, `components/schemas`)
  - **Architecture Decision Record** — MADR format covering context, decision, consequences, and alternatives considered
  - **Migration Runbook** — pre-conditions, numbered deployment steps, verification `curl` commands, and rollback procedure
- Each document saved as `Artifact(OPENAPI_SPEC | ADR | RUNBOOK)` under `docs/<service-name>/`
- **Validation**: the integration test parses the generated YAML with `swagger-parser` v2.1.22 and asserts zero parse errors
- **`POST /api/jobs/{id}/generate-docs/{boundaryId}`**

### Phase 3.4 — Bundle Assembler
*Commit: `feat(bundle): Phase 3.4`*

- **`BundleAssembler`** reads all artifacts for a job from PostgreSQL and reconstructs the full directory tree using each artifact's `file_path` column:
  - `SERVICE_CODE` / `TEST_CODE` / `OPENAPI_SPEC` / `ADR` / `RUNBOOK` → `<service-name>/<file_path>`
  - `ORIGINAL` / `TRANSFORMED` → excluded from the output bundle
- Generates two root-level files:
  - **`pom.xml`** — multi-module Maven wrapper with Spring Boot 3.2.5 parent, one `<module>` per service
  - **`docker-compose.yml`** — one service block per microservice (ports 8081+), shared `postgres:16-alpine` database
- Packages everything into a ZIP via `ZipOutputStream`
- **`GET /api/jobs/{id}/bundle`** — returns `application/zip` with `Content-Disposition: attachment`
- **★ End-to-end milestone test** (`BundleEndToEndTest`): seeds the canonical owner-service artifacts, downloads the bundle via HTTP, unzips it to a temp directory, runs `mvn compile -q`, and asserts exit code 0

---

## REST API quick reference

```
# Job lifecycle
POST   /api/jobs                              Create a new migration job
GET    /api/jobs                              List all jobs
GET    /api/jobs/{id}/status                  Get job status
POST   /api/jobs/{id}/advance                 Advance to next state (admin)
POST   /api/jobs/{id}/retry                   Reset FAILED → PENDING

# Analysis pipeline
POST   /api/jobs/{id}/analyze                 Phase 1: parse → Neo4j → cluster

# Code generation
POST   /api/jobs/{id}/architect               Phase 2.4: LLM → service boundaries
POST   /api/jobs/{id}/generate-service/{bid}  Phase 3.1: LLM → microservice source
POST   /api/jobs/{id}/generate-tests/{bid}    Phase 3.2: LLM → JUnit 5 + MockMvc tests
POST   /api/jobs/{id}/generate-docs/{bid}     Phase 3.3: LLM → OpenAPI + ADR + Runbook

# Bundle
GET    /api/jobs/{id}/bundle                  Phase 3.4: download ZIP of all generated files

# RAG indexing
POST   /api/rag/{id}/index                    Index source files into Qdrant
```

---

## Quick start

**Prerequisites:** Java 21, Maven 3.9+, Docker, Python 3.11 (for RAG indexing)

```bash
# 1. Start all infrastructure
docker compose up -d

# 2. Build the project
mvn install -q

# 3. Run the application
cd orchestrator && mvn spring-boot:run
```

Set `ANTHROPIC_API_KEY` in your environment to use Claude. Without it, the application falls back to Ollama (requires `codellama:13b` pulled locally).

```bash
# To use Claude
export ANTHROPIC_API_KEY=sk-ant-...
```

All HTTP examples are in `requests.http` (IntelliJ HTTP Client format).

---

## Test coverage

| Module | Test classes | Tests |
|---|---|---|
| core | 3 | ~30 |
| agents | 10 | ~150 |
| rag | 2 | ~25 |
| orchestrator | 4 | ~60 |
| **Total** | **~19** | **~264** |

Integration tests (those requiring Postgres, Neo4j, or an LLM) are auto-skipped via `assumeTrue(portOpen(...))` or `@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY")`.

---

## What's next — Phase 4

The `evaluator` module is scaffolded (pom.xml + DB migration) but not yet implemented. It will score the quality of every generated artifact:

| Metric | What it measures |
|---|---|
| `COMPILATION_SUCCESS` | Does the generated Java parse without errors? |
| `TEST_PASS_RATE` | How many @Test methods are present; do they compile? |
| `SIMILARITY_SCORE` | Jaccard similarity on method-name sets (original vs generated) |
| `HALSTEAD_COMPLEXITY` | Volume, difficulty, and effort — are the generated services simpler than the original? |
