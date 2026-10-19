# Load test baseline (US-018)

**This is a baseline, not a requirement.** No volume target (35–50M/day, 800–1,500/s) is assumed; numbers below are just what one run measured.

Reproduce: `loadtest/run.sh [concurrency...]` (needs docker, JDK 17, Maven, python3; writes `loadtest/results.txt`).
Scenario: `POST /api/v1/executions` on flow START → VALIDATION (`loadtest-fake`, instant, only registered under Spring profile `loadtest`) → END, single tenant, closed loop, 5s warmup + 30s measured per level.

Reference machine (everything on one box, so driver/app/DB compete for CPU): AMD Ryzen 5 5600X 6C/12T, 16 GB RAM, WSL2, OpenJDK 17.0.20, Postgres 17.11 in Docker with default config, app defaults (Hikari pool 10, no tuning). Run 2026-10-03.

| concurrency | throughput (req/s) | p50 ms | p95 ms | p99 ms | errors |
|---|---|---|---|---|---|
| 8  | 553.5 | 13.8  | 19.1  | 22.0  | 0 |
| 32 | 665.4 | 49.2  | 54.2  | 58.1  | 0 |
| 64 | 664.7 | 100.0 | 106.8 | 112.2 | 0 |

Database: ~11 connections in use (Hikari default 10); the Postgres container stayed at ≤0.5% CPU and ≤200 MiB RAM at sampling time (`docker stats` is a point-in-time sample taken after each level, so it is indicative only). Per execution ≈ 8 commits and ≈ 6.5 inserted rows; DB grew to 143 MB after ~583k commits (~58k executions).

Reading: throughput plateaus near ~665 req/s from 32 clients upward while latency grows linearly with concurrency (p50 ≈ concurrency / throughput), i.e. the system is saturated at that point. The DB looks idle, so the limit is likely in the app (many small commits per execution, pool of 10, shared CPU with the driver) — not investigated further; profile before tuning. Python driver may itself cap throughput; re-run with k6/Gatling for a cleaner number.

## After the JPA migration (US-007)

Same machine, scenario and `loadtest/run.sh`; run 2026-10-08, Postgres 17.11.

| concurrency | req/s | vs JDBC | p50 ms | p95 ms | p99 ms | errors |
|---|---|---|---|---|---|---|
| 8  | 499.5 | -9.8% | 15.1  | 21.1  | 25.5  | 0 |
| 32 | 629.6 | -5.4% | 51.6  | 58.6  | 66.0  | 0 |
| 64 | 632.7 | -4.8% | 104.9 | 113.8 | 123.5 | 0 |

No level dropped more than 10%, so no finding is raised. The 8-client drop (9.8%) is at the threshold; single runs on a shared box vary about that much, so treat it as noise-level. p95 at 64 went from 106.8 to 113.8 ms.

Statements per execution (Postgres `log_statement=all`, 1 client; JDBC = commit before US-002): both run **9 statements** (1 flow SELECT, 1 execution INSERT, 3 node INSERTs, 2 audit INSERTs, 2 execution UPDATEs). JPA issues **no SELECT before INSERT** (`Persistable<UUID>` works). The difference is transactions: JPA adds 8 explicit `COMMIT` round trips per execution that the JDBC autocommit path does not log; likely cause of the small loss. Accepted, not fixed.

## After provider config + pause/resume (US-013)

Same machine, scenario and `loadtest/run.sh`; run 2026-10-13 (two full runs, plus one level-8 run on the commit before US-013).

| concurrency | req/s (run 2) | vs JPA baseline | req/s (run 1) | p50 ms | p95 ms | p99 ms | errors |
|---|---|---|---|---|---|---|---|
| 8  | 367.1 | -26.5% | 375.9 | 20.9  | 29.1  | 35.3  | 0 |
| 32 | 497.8 | -20.9% | 507.8 | 56.5  | 102.0 | 133.0 | 0 |
| 64 | 634.5 | +0.3%  | 617.9 | 103.9 | 114.5 | 127.7 | 0 |

**Correction (bisect, same day): the drop above does not reproduce.** The runs above were taken while the Ralph loop was still running on the same machine (its `claude` and `mvn verify` processes compete for the 12 CPUs), so they are contaminated. Re-run with nothing else going on, one worktree per commit, `loadtest/run.sh <level>`:

| commit | 8 clients (req/s) | 32 clients (req/s) |
|---|---|---|
| `9b331c2` (before this branch) | 608.0 | 716.5 |
| `ed38d65` (US-013) | 606.3 / 591.8 | 679.1 |

That is -0.3% to -2.7% at 8 clients and -5.2% at 32, inside the 10% threshold and the run-to-run noise noted above. No bisect of US-004..US-012 is needed. p95 at 8 clients: 19.6 ms vs 18.6 / 19.4 ms.

One real difference remains: Postgres `blks_hit` per execution is higher on this branch (about 3.3k vs 1.9k at 8 clients, +70%; +29% at 32), with no throughput cost today. Worth a look if the DB ever becomes the bottleneck (candidates: the new `flow_execution` columns/partial index from V5, `provider_config` lookups). Not investigated.

## After async providers + cache (US-012)

Run 2026-10-14, same machine and `loadtest/run.sh`, levels 8 and 32 (level 8 repeated). Only this Claude session was alive (`claude` ~2% CPU), no `mvn` or other loop running, but load average was 3-6 from the preceding build, so the box may not have been fully idle.

| concurrency | req/s | vs idle baseline (`9b331c2`) | p50 ms | p95 ms | p99 ms | errors |
|---|---|---|---|---|---|---|
| 8  | 382.3 / 371.2 | -37% / -39% | 20.4 | 27.8 | 32.6 | 0 |
| 32 | 531.8 | -26% | 53.9 | 91.7 | 109.0 | 0 |

**Drop above 10%, recorded as a finding.** It is reproducible (two level-8 runs agree) and matches the "contaminated" numbers of the US-013 section (367-376 at 8 clients), so it is either a residual-load artefact of this setup or a real cost of the new cache lookups: the scenario's VALIDATION node (`loadtest-fake`) is not provider-backed, so the cache path should not run, but `provider_config`/`ProviderCache` beans and V6-V9 migrations are new. Not bisected; rerun on a truly idle machine per commit before acting.

## Drop explained: it was the working directory, not the code (2026-10-14)

Box idle (load average 1-2, nothing else running), each tree built and run separately, levels 8 / 32 (req/s):

| tree | location | 8 clients | 32 clients |
|---|---|---|---|
| `9b331c2` (before the provider work) | `/tmp` worktree | 584.0 | 818.6 |
| `9760ca2` (tip of this branch, US-012) | `/tmp` worktree | 614.3 / 584.6 | 791.3 / 812.3 |
| `9760ca2` + V10 (FK + indexes) | `/tmp` worktree | 596.1 | 807.6 |
| `9760ca2` + V10 | `/workspace` (Windows `C:` mount) | 380.0 | 525.1 |

The same code loses ~35% only when built and run from `/workspace`: `loadtest/run.sh` writes `app.log` (one INFO line per node) there, and the mount is much slower than the container filesystem. The earlier "drop" findings (US-013 and US-012 sections above) were all taken in `/workspace`, so they measure the mount, not the branch; the async/cache work costs 0-2% on this scenario (within noise). **Run the load test from a path on the container filesystem** (e.g. a `git worktree` under `/tmp`). The `blks_hit` increase noted after US-013 was also measured in `/workspace` and was not re-checked.

## After operation + keyFields + normalized key (cache-operacao-e-chave, 2026-10-19)

Run from a `/tmp` worktree at `e7ebbd8`, `loadtest/run.sh 8 32`, box idle (load average ~1.4, `mvn verify` finished before the run).

| concurrency | req/s | vs idle baseline (584.0 / 818.6) | p50 ms | p95 ms | p99 ms | errors |
|---|---|---|---|---|---|---|
| 8  | 619.8 | +6.1% | 11.5 | 18.3 | 21.9 | 0 |
| 32 | 796.7 | -2.7% | 38.5 | 61.7 | 79.2 | 0 |

No drop above 10%: the difference is within run-to-run noise. The scenario's node is not provider-backed, so the new key code (`cacheKeyParams`, `keyFields`) is not exercised by this load; this only shows it adds no cost to the non-cache path.
