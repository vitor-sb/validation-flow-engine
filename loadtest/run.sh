#!/usr/bin/env bash
# Reproducible baseline: Postgres (docker) + app (profile loadtest) + load.py. Writes loadtest/results.txt.
# Usage: loadtest/run.sh [CONCURRENCY...]   (default: 8 32 64; 30s measured each after 5s warmup)
set -euo pipefail
cd "$(dirname "$0")/.."
LEVELS=("${@:-8 32 64}"); [ $# -eq 0 ] && LEVELS=(8 32 64)
PG=vfe-loadtest-pg; PORT=18080; KEY=loadkey
docker rm -f $PG >/dev/null 2>&1 || true
docker run -d --name $PG -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=vfe -p 15432:5432 postgres:17 >/dev/null
trap 'kill ${APP:-0} 2>/dev/null || true; docker rm -f $PG >/dev/null 2>&1 || true' EXIT
until docker exec $PG pg_isready -U postgres -d vfe >/dev/null 2>&1; do sleep 1; done
# reach Postgres by container IP when the docker daemon is a sibling container (published ports are then on the host, not localhost)
DB=localhost:15432
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $PG)
if [ -n "$IP" ] && timeout 2 bash -c "</dev/tcp/$IP/5432" 2>/dev/null; then DB=$IP:5432; fi
mvn -q -B -DskipTests package
SPRING_PROFILES_ACTIVE=loadtest SERVER_PORT=$PORT \
SPRING_DATASOURCE_URL=jdbc:postgresql://$DB/vfe SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=pw \
APP_SECURITY_API_KEYS_0_KEY=$KEY APP_SECURITY_API_KEYS_0_TENANT_ID=load \
APP_SECURITY_API_KEYS_0_SCOPES=flow:write,flow:activate,validation:read,validation:execute \
  java -jar target/*.jar >loadtest/app.log 2>&1 & APP=$!
until curl -sf localhost:$PORT/actuator/health >/dev/null; do sleep 1; done
psql_() { docker exec $PG psql -U postgres -d vfe -Atc "$1"; }
{
  echo "date: $(date -Is)"; echo "cpu: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2) x$(nproc)"
  echo "mem_mb: $(free -m | awk '/Mem:/{print $2}')"; echo "java: $(java -version 2>&1 | head -1)"
  echo "postgres: $(psql_ 'show server_version') (docker, default config)"; echo "note: app, DB and load driver share one machine"
  for c in "${LEVELS[@]}"; do
    before=$(psql_ "select xact_commit, tup_inserted, blks_read, blks_hit from pg_stat_database where datname='vfe'")
    python3 loadtest/load.py http://localhost:$PORT $KEY "$c" 30 5
    after=$(psql_ "select xact_commit, tup_inserted, blks_read, blks_hit from pg_stat_database where datname='vfe'")
    echo "  db delta (commits|rows inserted|blks_read|blks_hit, incl. warmup): $before -> $after"
    echo "  db: $(psql_ "select count(*) from pg_stat_activity where datname='vfe'") connections, size $(psql_ "select pg_size_pretty(pg_database_size('vfe'))")"
    echo "  pg container: $(docker stats --no-stream --format 'cpu {{.CPUPerc}} mem {{.MemUsage}}' $PG)"
  done
} | tee loadtest/results.txt
