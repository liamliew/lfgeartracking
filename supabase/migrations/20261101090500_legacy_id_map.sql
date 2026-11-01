-- legacy_id_map: traceability from rows of the old system to rows of this one. Written by scripts/legacy
-- (run by a database owner, not through the API). Only admins may read it from the app.

create table public.legacy_id_map (
  legacy_table text not null,
  legacy_id text not null,
  new_table text not null,
  new_id uuid not null,
  imported_at timestamptz not null default now(),
  primary key (legacy_table, legacy_id)
);
create index legacy_id_map_new_idx on public.legacy_id_map (new_table, new_id);

alter table public.legacy_id_map enable row level security;
create policy legacy_id_map_admin_select on public.legacy_id_map for select to authenticated
  using ((select private.is_admin()));

revoke all on public.legacy_id_map from anon, authenticated;
grant select on public.legacy_id_map to authenticated;
grant all on public.legacy_id_map to service_role;
