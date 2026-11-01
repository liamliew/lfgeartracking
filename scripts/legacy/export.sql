-- READ-ONLY export of the legacy inventory data to JSON files, one per table, for scripts/legacy/src (the importer).
--
-- How to run (from the scripts/legacy folder, with a READ-ONLY database role if you have one):
--   mkdir -p export
--   psql "$LEGACY_DATABASE_URL" -X -v ON_ERROR_STOP=1 -f export.sql
-- The files appear in ./export/ (gitignored). Do not commit them and do not send them to anyone who should not
-- see the inventory. This script never writes to the database: it runs in a READ ONLY transaction that is rolled back.
--
-- DELIBERATELY NOT EXPORTED
--   inventory_team_members      contains PINs and passwords. Credentials are never exported or migrated:
--                               re-invite people through Supabase Auth in the new system.
--   inventory_employee_settings and anything that is not inventory data.
--   inventory_sessions, inventory_session_items   transient working state, not migrated.
--   job tables (inventory_jobs, shoot days, job assets, media, team)   empty, ignored.
--
-- WHAT IS EXPORTED: every column of the seven tables below (select *). The importer refuses any file that has a
-- credential-like column, and this script stops first if one exists.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on

begin read only;

do $$
declare
  bad text;
begin
  select string_agg(table_name || '.' || column_name, ', ') into bad
  from information_schema.columns
  where table_schema = 'public'
    and table_name in ('inventory_assets', 'inventory_containers', 'inventory_locations', 'inventory_events',
                       'inventory_container_events', 'inventory_audits', 'inventory_audit_items')
    and column_name ~* '(^|_)(pin|passw(or)?d|passcode|secret|token|hash|salt|credential|api_?key)(_|$)';
  if bad is not null then
    raise exception 'Refusing to export credential-like columns: %. Remove them from the export (select explicit columns) and tell the engineer.', bad;
  end if;
end $$;

-- \o sends the raw query result to a file (unlike \copy it does not escape backslashes, so the JSON stays valid).
\o export/inventory_assets.json
select coalesce(json_agg(t), '[]'::json) from (select * from public.inventory_assets) t;

\o export/inventory_containers.json
select coalesce(json_agg(t), '[]'::json) from (select * from public.inventory_containers) t;

\o export/inventory_locations.json
select coalesce(json_agg(t), '[]'::json) from (select * from public.inventory_locations) t;

\o export/inventory_events.json
select coalesce(json_agg(t), '[]'::json) from (select * from public.inventory_events) t;

\o export/inventory_container_events.json
select coalesce(json_agg(t), '[]'::json) from (select * from public.inventory_container_events) t;

\o export/inventory_audits.json
select coalesce(json_agg(t), '[]'::json) from (select * from public.inventory_audits) t;

\o export/inventory_audit_items.json
select coalesce(json_agg(t), '[]'::json) from (select * from public.inventory_audit_items) t;

\o
rollback;
