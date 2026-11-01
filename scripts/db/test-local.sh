#!/usr/bin/env bash
# Runs migrations + seed + pgTAP tests against a throwaway local PostgreSQL (no Docker needed).
# Prereqs (Debian/Ubuntu): postgresql-16 (or newer), postgresql-16-pgtap, pgtap's pg_prove.
# CI uses the real stack instead: `supabase start && supabase db reset && supabase test db`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PGBIN="$(ls -d /usr/lib/postgresql/*/bin | sort -V | tail -1)"
DATA="${GT_PGDATA:-/tmp/gt-pgdata}"
PORT="${GT_PGPORT:-54329}"
SOCK="/tmp"
DB=gt_test

as_pg() { if [ "$(id -u)" = 0 ]; then runuser -u postgres -- "$@"; else "$@"; fi; }
psql_() { as_pg "$PGBIN/psql" -X -q -h "$SOCK" -p "$PORT" -U postgres -v ON_ERROR_STOP=1 "$@"; }

cleanup() { as_pg "$PGBIN/pg_ctl" -D "$DATA" -m immediate stop >/dev/null 2>&1 || true; }
trap cleanup EXIT

cleanup
rm -rf "$DATA"; mkdir -p "$DATA"
[ "$(id -u)" = 0 ] && chown postgres:postgres "$DATA"
as_pg "$PGBIN/initdb" -D "$DATA" -A trust -U postgres >/dev/null
as_pg "$PGBIN/pg_ctl" -D "$DATA" -w -l "$DATA/log" \
  -o "-p $PORT -k $SOCK -c listen_addresses='' -c fsync=off" start >/dev/null

psql_ -d postgres -c "create database $DB"
psql_ -d "$DB" -c "alter database $DB set search_path to public, extensions"
psql_ -d "$DB" -f "$ROOT/scripts/db/supabase-shim.sql"
for f in "$ROOT"/supabase/migrations/*.sql; do
  echo "apply $(basename "$f")"
  psql_ -d "$DB" -f "$f"
done
[ -f "$ROOT/supabase/seed.sql" ] && psql_ -d "$DB" -f "$ROOT/supabase/seed.sql"

as_pg pg_prove -h "$SOCK" -p "$PORT" -U postgres -d "$DB" "$@" "$ROOT"/supabase/tests/*.sql
