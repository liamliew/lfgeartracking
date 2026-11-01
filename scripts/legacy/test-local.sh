#!/usr/bin/env bash
# Runs the importer's tests, INCLUDING the database integration tests, against a throwaway local PostgreSQL
# (real migrations + seed, Supabase shim; no Docker). In CI the same tests run against `supabase start`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PGBIN="$(ls -d /usr/lib/postgresql/*/bin | sort -V | tail -1)"
DATA="${GT_PGDATA:-/tmp/gt-legacy-pgdata}"; PORT="${GT_PGPORT:-54331}"; SOCK=/tmp; DB=gt_legacy
as_pg() { if [ "$(id -u)" = 0 ]; then runuser -u postgres -- "$@"; else "$@"; fi; }
psql_() { as_pg "$PGBIN/psql" -X -q -h "$SOCK" -p "$PORT" -U postgres -v ON_ERROR_STOP=1 "$@"; }
cleanup() { as_pg "$PGBIN/pg_ctl" -D "$DATA" -m immediate stop >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup; rm -rf "$DATA"; mkdir -p "$DATA"; [ "$(id -u)" = 0 ] && chown postgres:postgres "$DATA"
as_pg "$PGBIN/initdb" -D "$DATA" -A trust -U postgres >/dev/null
as_pg "$PGBIN/pg_ctl" -D "$DATA" -w -l "$DATA/log" -o "-p $PORT -k $SOCK -c listen_addresses='' -c fsync=off" start >/dev/null
psql_ -d postgres -c "create database $DB"
psql_ -d "$DB" -c "alter database $DB set search_path to public, extensions"
psql_ -d "$DB" -f "$ROOT/scripts/db/supabase-shim.sql" >/dev/null
for f in "$ROOT"/supabase/migrations/*.sql; do psql_ -d "$DB" -f "$f" >/dev/null 2>&1; done
psql_ -d "$DB" -f "$ROOT/supabase/seed.sql" >/dev/null
cd "$ROOT/scripts/legacy"
LEGACY_TEST_DB_URL="postgresql://postgres@/$DB?host=$SOCK&port=$PORT" npx vitest run "$@"
