# ADR 0001: printed codes are tags; Docker-less DB test runner

## Status
Accepted (task 2).

## Context
1. The spec has one lookup for everything (`tags.value`) but also prints codes (A000123) on labels. Scanning a printed label must resolve even if nobody assigned a separate tag. Pre-printed label sheets may be scanned before the asset exists.
2. The cloud session has no Docker daemon, so `supabase start` / `db reset` cannot run there.

## Decision
1. Inserting an asset or location also inserts a barcode tag for its code. A barcode shaped like `A######` / `C######` passed to `create_asset` becomes the asset code. The generator skips codes already used as assets or tags.
2. `scripts/db/test-local.sh` starts a throwaway PostgreSQL, loads `scripts/db/supabase-shim.sql` (roles, `auth.users`, `auth.uid()`), applies the real migrations and seed, and runs the pgTAP files. CI keeps the real flow (`supabase start`, `supabase db reset`, `supabase test db`), so the shim is only a convenience and never the source of truth.

## Consequences
Tag lookups are a single primary-key read. Retired tags keep their value reserved. Differences between the shim and real Supabase are possible; CI is the arbiter.
