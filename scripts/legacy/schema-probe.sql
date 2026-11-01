-- READ-ONLY. Run this FIRST, against the legacy database, and look at the output before exporting anything:
--   psql "$LEGACY_DATABASE_URL" -X -f schema-probe.sql
-- It lists the columns of the tables the importer reads, so the importer's assumed column names
-- (scripts/legacy/src/types.ts, DEFAULT_COLUMNS) can be checked against the real ones. It reads no data.
\set ON_ERROR_STOP on
begin read only;

select table_name, ordinal_position as pos, column_name, data_type
from information_schema.columns
where table_schema = 'public'
  and table_name in ('inventory_assets', 'inventory_containers', 'inventory_locations', 'inventory_events',
                     'inventory_container_events', 'inventory_audits', 'inventory_audit_items')
order by table_name, ordinal_position;

-- Any credential-like column in those tables? There should be none; if there is, tell the engineer BEFORE exporting.
select table_name, column_name as credential_like_column
from information_schema.columns
where table_schema = 'public'
  and table_name in ('inventory_assets', 'inventory_containers', 'inventory_locations', 'inventory_events',
                     'inventory_container_events', 'inventory_audits', 'inventory_audit_items')
  and column_name ~* '(^|_)(pin|passw(or)?d|passcode|secret|token|hash|salt|credential|api_?key)(_|$)';

rollback;
