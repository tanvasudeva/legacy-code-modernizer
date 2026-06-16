# Benchmark Run Guide

Everything you need to run the full evaluation tomorrow and get a results table.

---

## What the benchmark does

Runs your full pipeline on each of 20 repos and measures 6 metrics against 2 baselines.
At the end you get `results/analysis/analysis_report.md` — a Markdown table that goes
directly into your SOP and README.

**Time estimate:** 4–8 hours for all 20 repos end-to-end (LLM latency dominates).
Run petclinic first (~15 min) to verify everything works before leaving it overnight.

---

## Step 1 — Start infrastructure

```bash
docker compose up -d

# Verify all 4 services are healthy
docker compose ps
```

Expected output — all 4 should show `healthy`:

```
NAME           STATUS
lcm-postgres   running (healthy)
lcm-redis      running (healthy)
lcm-neo4j      running (healthy)    ← takes ~40s to start
lcm-qdrant     running (healthy)
```

If Neo4j is still starting, wait 60 seconds and re-check before moving on.

---

## Step 2 — Set API keys

```bash
export ANTHROPIC_API_KEY=sk-ant-...       # required — Claude generates all code
export OPENAI_API_KEY=sk-...              # required — GPT-4o baseline + cross-model judge
```

Both keys must be set. The pipeline will fail silently on the GPT-4o baseline and the
cross-model judge if `OPENAI_API_KEY` is missing.

---

## Step 3 — Build and start the server

```bash
# From the project root
mvn install -q -DskipTests

cd orchestrator
mvn spring-boot:run
```

Leave this terminal running. Open a second terminal for all the commands below.

**Verify the server is up:**
```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
# Should return: {"status": "UP"}
```

---

## Step 4 — Verify one repo end-to-end first

Always run petclinic first. It is the smallest repo (~5k LOC) and completes in ~15 minutes.
If this fails, fix it before running everything.

```bash
curl -s -X POST http://localhost:8080/api/benchmark/spring-petclinic | python3 -m json.tool
```

Expected response fields:
```json
{
  "repoName":            "spring-petclinic",
  "serviceCount":        4,
  "totalFilesGenerated": 28,
  "compilationRate":     0.75,
  "errorMessage":        null
}
```

- `compilationRate > 0` — repair loop is running
- `serviceCount >= 2` — ArchitectAgent and DD2 detection both ran
- `errorMessage: null` — clean run

If you get an error, check the server log first. Most failures are:
- Neo4j not fully started → wait 60 more seconds
- Missing API key → check Step 2
- Source directory not found → check `benchmarks/spring-petclinic/src` exists

---

## Step 5 — Run the full multi-agent pipeline (all 20 repos)

```bash
python3 scripts/run_benchmarks.py
```

This calls `POST /api/benchmark/{repo}` sequentially for all 20 repos with a 3600s
per-repo timeout. Progress is printed as each repo completes:

```
[1/20] spring-petclinic (~5,000 LOC) …
  ✓ done in 847s — services=4, files=28, tokens=41230
[2/20] HikariCP (~15,000 LOC) …
  ✓ done in 1203s — services=6, files=42, tokens=67890
...
```

**Run only specific repos** (useful for retrying failures):
```bash
python3 scripts/run_benchmarks.py --repos spring-petclinic,HikariCP,jforum3
```

Results are written to `results/{repo}/multi-agent/` as each repo finishes.
The run summary is saved to `results/benchmark_run_summary.json`.

---

## Step 6 — Run both single-prompt baselines

```bash
python3 scripts/run_benchmarks.py --baselines-only
```

This calls `POST /api/baseline/{repo}/gpt4o` and `POST /api/baseline/{repo}/claude`
for all 20 repos. Each baseline:
1. Sends the full source (up to token budget) to the LLM in a single prompt
2. Saves `response_raw.txt` and `response.json` to `results/{repo}/{system}/`
3. `BaselineCodeExtractor` then attempts `mvn compile` on extracted Java blocks

Baseline runs are faster than multi-agent (~5–15 min per repo per system).

---

## Step 7 — Run evaluation (all 6 metrics, all systems)

```bash
curl -s -X POST http://localhost:8080/api/eval/all | python3 -m json.tool
```

This triggers `EvaluatorService` for every job in the DB, computing:

| Metric | What runs |
|---|---|
| Compilation | `mvn compile -q` per service, first-attempt + post-repair rates |
| Coverage | `mvn test` + JaCoCo XML parse |
| API completeness | JavaParser method-name overlap vs original source |
| LLM judge | Cross-model 5-dimension score (Claude↔GPT-4o) |
| Inter-service coupling | Neo4j CALLS cross-boundary ratio |
| LCOM4 cohesion | JavaParser Union-Find per generated class |

Results are persisted to `eval_metrics` in PostgreSQL.

---

## Step 8 — Generate the results report

```bash
python3 scripts/analyze_results.py \
  --db-url postgresql://postgres:postgres@localhost/lcm \
  --results-dir results
```

Outputs written to `results/analysis/`:

| File | Contents |
|---|---|
| `analysis_report.md` | Main results table + repair analysis + cohesion/coupling section + per-repo breakdown |
| `analysis_report.tex` | LaTeX booktabs table — paste into your SOP/paper |
| `summary_stats.json` | Mean ± std per metric × system |
| `wilcoxon_tests.json` | W statistic and p-value per comparison |
| `plots/` | Per-metric boxplots, per-repo heatmap, LOC scatter with regression |

**If you don't want to wait for Step 5–7** (testing the analysis script works):
```bash
python3 scripts/analyze_results.py --synthetic
```
This generates realistic synthetic data and writes the same outputs — useful for
verifying the report format before real data is ready.

---

## Step 9 — Verify the dashboard

```bash
cd frontend && npm run dev
# Open http://localhost:5173
```

- Click any completed job → should show phase bar at DONE, service cards, metrics panel
- Expand a service card → file tree + "View code" should open `CodeViewer`
- If a job has a commons module, it appears above the service list with the amber badge
- "↓ Download Bundle" on a DONE job triggers the ZIP download

---

## Common failure modes and fixes

**Neo4j connection refused at startup**
```bash
docker compose restart neo4j
# Wait 60 seconds, then restart the Spring Boot server
```

**`Source directory not found` error for a repo**
```bash
ls benchmarks/        # Confirm the repo is cloned
# If missing:
git clone --depth=1 https://github.com/.../{repo}.git benchmarks/{repo}
```

**Compilation repair loop stuck**
The default timeout is 120s per `mvn compile` call. For large repos (dbeaver, Activiti),
a single repair iteration can take 3–4 minutes. This is expected — just wait.

**GPT-4o baseline returns 429 (rate limit)**
```bash
# Re-run only the GPT-4o baseline for the affected repo
python3 scripts/run_benchmarks.py --baselines-only --repos {repo}
```

**`eval/all` times out**
Run per-job instead:
```bash
for id in 1 2 3 4 5; do
  curl -s -X POST http://localhost:8080/api/eval/$id/all > /dev/null
  echo "Job $id evaluated"
done
```

**Qdrant collection not found during RAG indexing**
The RAG index is built automatically as part of `POST /api/benchmark/{repo}`.
If it fails, manually trigger:
```bash
curl -s -X POST http://localhost:8080/api/rag/{jobId}/index
```

---

## Useful monitoring commands

```bash
# Watch benchmark progress in server log
tail -f orchestrator/target/*.log 2>/dev/null || \
  # (if no log file, the server stdout shows progress)

# Count how many jobs are DONE vs still running
curl -s http://localhost:8080/api/jobs | \
  python3 -c "import sys,json; jobs=json.load(sys.stdin); \
  [print(j['name'], j['status']) for j in jobs]"

# Check repair stats for a specific job
curl -s http://localhost:8080/api/jobs/{id}/repair-stats | python3 -m json.tool

# Check shared library detection results
curl -s http://localhost:8080/api/jobs/{id}/shared-classes | python3 -m json.tool

# Quick metric check for one job (before running full analysis)
curl -s http://localhost:8080/api/eval/{id} | \
  python3 -c "import sys,json; \
  [print(f\"{m['metricName']:<35} {m['systemId']:<25} {m['metricValue']}\") \
   for m in json.load(sys.stdin)]"
```

---

## What good results look like

For the multi-agent system across 20 repos you should see approximately:

| Metric | Expected range |
|---|---|
| Compilation (post-repair) | 0.60 – 0.85 |
| Compilation (first attempt) | 0.40 – 0.65 |
| Coverage | 0.05 – 0.25 (low — tests need live DB) |
| API completeness | 0.70 – 0.90 |
| LLM judge | 6.5 – 8.5 |
| Inter-service coupling | 0.08 – 0.20 |
| Avg LCOM4 | 1.1 – 1.5 |
| Perfect cohesion % | 0.55 – 0.80 |

Single-prompt baselines should show:
- Compilation ≈ 0.0 (no code generated by default; `BaselineCodeExtractor` may recover some)
- API completeness: 0.45 – 0.70 (class-name overlap from JSON plan)
- LLM judge: 5.5 – 7.5

If multi-agent compilation is below 0.50 after repair, check that the repair loop ran
(look for `[repair]` lines in the server log for that repo).

---

## End of run checklist

- [ ] `results/benchmark_run_summary.json` exists and shows `succeeded: 20`
- [ ] `results/analysis/analysis_report.md` has all 10 metric rows populated (not all `—`)
- [ ] `results/analysis/plots/` contains at least 4 PNG files
- [ ] Wilcoxon p-values are present (not all `n/a`) — need ≥ 4 repos with both systems measured
- [ ] Dashboard shows all 20 jobs at DONE status
- [ ] Copy `results/analysis/analysis_report.tex` table into your SOP
