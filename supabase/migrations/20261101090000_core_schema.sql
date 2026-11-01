-- Gear Tracker core schema: types, tables, integrity triggers, views, RLS and grants.
-- Docs consulted:
--   https://supabase.com/docs/guides/database/postgres/row-level-security
--   https://supabase.com/docs/guides/database/functions  (security definer + search_path)
-- Conventions:
--   * Helper / trigger functions live in the non-exposed `private` schema.
--   * Every function sets `search_path = ''` and schema-qualifies all references.
--   * Policies wrap auth helpers in `(select ...)` so they are evaluated once per statement.
--   * Crew devices never write projection tables directly; they use the RPCs in the next migration.

create schema if not exists private;
create schema if not exists extensions;
create extension if not exists pg_trgm with schema extensions;

-- ---------------------------------------------------------------------------
-- Types
-- ---------------------------------------------------------------------------
create type public.app_role as enum ('admin', 'manager', 'crew', 'viewer');
create type public.tag_kind as enum ('barcode', 'nfc');
create type public.asset_status as enum ('available', 'checked_out', 'in_repair', 'lost', 'retired');
create type public.event_type as enum (
  'created', 'tag_assigned', 'placed_in_location', 'placed_in_container', 'removed_from_container',
  'checked_out', 'checked_in', 'seen', 'audited_found', 'audited_missing', 'status_changed', 'edited'
);
create type public.location_kind as enum ('site', 'room', 'shelf', 'cabinet', 'bin', 'vehicle', 'other');
create type public.audit_result as enum ('found', 'missing', 'unexpected');

create sequence public.asset_code_seq;      -- A000123
create sequence public.container_code_seq;  -- C000045
create sequence public.location_code_seq;  -- L00312

-- ---------------------------------------------------------------------------
-- Profiles and role helpers
-- ---------------------------------------------------------------------------
create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  display_name text not null default '',
  role public.app_role not null default 'viewer',
  created_at timestamptz not null default now()
);

create function private.user_role() returns public.app_role
language sql stable security definer set search_path = '' as $$
  select p.role from public.profiles p where p.id = (select auth.uid())
$$;

create function private.is_member() returns boolean
language sql stable security definer set search_path = '' as $$
  select private.user_role() is not null
$$;

create function private.is_crew_plus() returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce(private.user_role() in ('crew', 'manager', 'admin'), false)
$$;

create function private.is_manager_plus() returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce(private.user_role() in ('manager', 'admin'), false)
$$;

create function private.is_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce(private.user_role() = 'admin', false)
$$;

create function private.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, display_name)
  values (
    new.id,
    coalesce(nullif(new.raw_user_meta_data ->> 'display_name', ''), split_part(coalesce(new.email, ''), '@', 1))
  )
  on conflict (id) do nothing;
  return new;
end
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function private.handle_new_user();

create function private.touch_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end
$$;

-- ---------------------------------------------------------------------------
-- Categories and templates
-- ---------------------------------------------------------------------------
create table public.categories (
  id uuid primary key default gen_random_uuid(),
  name text not null unique check (btrim(name) <> ''),
  parent_id uuid references public.categories (id) on delete restrict,
  field_schema jsonb not null default '{}'::jsonb,
  default_is_container boolean not null default false,
  created_at timestamptz not null default now()
);

create table public.asset_templates (
  id uuid primary key default gen_random_uuid(),
  name text not null unique check (btrim(name) <> ''),
  category_id uuid references public.categories (id) on delete set null,
  defaults jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Locations (arbitrary depth, cycle-safe, cached path)
-- ---------------------------------------------------------------------------
create table public.locations (
  id uuid primary key default gen_random_uuid(),
  code text not null unique default ('L' || lpad(nextval('public.location_code_seq')::text, 5, '0')),
  name text not null check (btrim(name) <> ''),
  kind public.location_kind not null default 'other',
  parent_id uuid references public.locations (id) on delete restrict,
  path_cache text not null default '',
  archived_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint locations_not_self check (parent_id is null or parent_id <> id)
);
create index locations_parent_idx on public.locations (parent_id);

create function private.locations_before_write() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_cycle boolean := false;
  v_anc_depth int := 0;
  v_desc_depth int := 0;
  v_parent_path text;
begin
  new.name := btrim(new.name);
  if new.parent_id is null then
    new.path_cache := new.name;
    return new;
  end if;

  with recursive anc as (
    select l.id, l.parent_id, 1 as depth from public.locations l where l.id = new.parent_id
    union all
    select l.id, l.parent_id, a.depth + 1
    from public.locations l join anc a on l.id = a.parent_id
    where a.depth < 50
  )
  select coalesce(bool_or(id = new.id), false), coalesce(max(depth), 0)
  into v_cycle, v_anc_depth from anc;

  if v_cycle then
    raise exception 'cycle detected: a location cannot be placed inside itself or its own children'
      using errcode = '23514';
  end if;

  with recursive d as (
    select l.id, 1 as depth from public.locations l where l.parent_id = new.id
    union all
    select l.id, d.depth + 1 from public.locations l join d on l.parent_id = d.id where d.depth < 50
  )
  select coalesce(max(depth), 0) into v_desc_depth from d;

  if v_anc_depth + 1 + v_desc_depth > 10 then
    raise exception 'location hierarchy too deep (max 10 levels)' using errcode = '23514';
  end if;

  select l.path_cache into v_parent_path from public.locations l where l.id = new.parent_id;
  new.path_cache := coalesce(v_parent_path, '') || ' > ' || new.name;
  return new;
end
$$;

create trigger locations_before_write
  before insert or update of parent_id, name on public.locations
  for each row execute function private.locations_before_write();

create function private.locations_after_rename() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  with recursive d as (
    select l.id, new.path_cache || ' > ' || l.name as p
    from public.locations l where l.parent_id = new.id
    union all
    select l.id, d.p || ' > ' || l.name
    from public.locations l join d on l.parent_id = d.id
  )
  update public.locations l set path_cache = d.p from d where l.id = d.id;
  return null;
end
$$;

create trigger locations_after_rename
  after update of parent_id, name on public.locations
  for each row
  when (old.name is distinct from new.name or old.parent_id is distinct from new.parent_id)
  execute function private.locations_after_rename();

create trigger locations_touch before update on public.locations
  for each row execute function private.touch_updated_at();

-- ---------------------------------------------------------------------------
-- Assets
-- ---------------------------------------------------------------------------
create function private.generate_asset_code(p_container boolean) returns text
language plpgsql security definer set search_path = '' as $$
declare
  v text;
begin
  loop
    if p_container then
      v := 'C' || lpad(nextval('public.container_code_seq')::text, 6, '0');
    else
      v := 'A' || lpad(nextval('public.asset_code_seq')::text, 6, '0');
    end if;
    exit when not exists (select 1 from public.assets a where a.asset_code = v)
          and not exists (select 1 from public.tags t where t.value = v);
  end loop;
  return v;
end
$$;

create table public.assets (
  id uuid primary key default gen_random_uuid(),
  asset_code text not null unique,
  name text not null check (btrim(name) <> ''),
  category_id uuid references public.categories (id) on delete set null,
  manufacturer text,
  model text,
  serial_number text,
  is_container boolean not null default false,
  status public.asset_status not null default 'available',
  parent_location_id uuid references public.locations (id) on delete restrict,
  parent_asset_id uuid references public.assets (id) on delete restrict,
  home_location_id uuid references public.locations (id) on delete set null,
  last_seen_at timestamptz,
  last_seen_by uuid references public.profiles (id) on delete set null,
  last_seen_lat double precision,
  last_seen_lng double precision,
  last_seen_accuracy_m real,
  custom jsonb not null default '{}'::jsonb,
  notes text,
  retired_at timestamptz,
  created_by uuid references public.profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  search tsvector generated always as (
    to_tsvector('simple',
      coalesce(asset_code, '') || ' ' || coalesce(name, '') || ' ' || coalesce(manufacturer, '') || ' ' ||
      coalesce(model, '') || ' ' || coalesce(serial_number, ''))
  ) stored,
  constraint assets_single_parent check (num_nonnulls(parent_location_id, parent_asset_id) <= 1),
  constraint assets_not_own_parent check (parent_asset_id is null or parent_asset_id <> id)
);
create index assets_search_idx on public.assets using gin (search);
create index assets_name_trgm_idx on public.assets using gin (name extensions.gin_trgm_ops);
create index assets_serial_trgm_idx on public.assets using gin (serial_number extensions.gin_trgm_ops);
create index assets_parent_location_idx on public.assets (parent_location_id);
create index assets_parent_asset_idx on public.assets (parent_asset_id);
create index assets_category_idx on public.assets (category_id);
create index assets_status_idx on public.assets (status);
create index assets_last_seen_idx on public.assets (last_seen_at);

create function private.assets_before_write() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_parent_is_container boolean;
  v_cycle boolean := false;
  v_anc_depth int := 0;
  v_desc_depth int := 0;
begin
  if tg_op = 'INSERT' and new.asset_code is null then
    new.asset_code := private.generate_asset_code(new.is_container);
  end if;
  new.name := btrim(new.name);

  if tg_op = 'UPDATE' and old.is_container and not new.is_container
     and exists (select 1 from public.assets c where c.parent_asset_id = new.id) then
    raise exception 'cannot unset is_container while the asset holds items' using errcode = '23514';
  end if;

  if new.parent_asset_id is not null
     and (tg_op = 'INSERT' or new.parent_asset_id is distinct from old.parent_asset_id) then
    select a.is_container into v_parent_is_container from public.assets a where a.id = new.parent_asset_id;
    if not found then
      raise exception 'parent asset does not exist' using errcode = '23503';
    end if;
    if not v_parent_is_container then
      raise exception 'parent asset is not a container' using errcode = '23514';
    end if;

    with recursive anc as (
      select a.id, a.parent_asset_id, 1 as depth from public.assets a where a.id = new.parent_asset_id
      union all
      select a.id, a.parent_asset_id, c.depth + 1
      from public.assets a join anc c on a.id = c.parent_asset_id
      where c.depth < 50
    )
    select coalesce(bool_or(id = new.id), false), coalesce(max(depth), 0)
    into v_cycle, v_anc_depth from anc;

    if v_cycle then
      raise exception 'cycle detected: a container cannot be placed inside itself or its own contents'
        using errcode = '23514';
    end if;

    with recursive d as (
      select a.id, 1 as depth from public.assets a where a.parent_asset_id = new.id
      union all
      select a.id, d.depth + 1 from public.assets a join d on a.parent_asset_id = d.id where d.depth < 50
    )
    select coalesce(max(depth), 0) into v_desc_depth from d;

    if v_anc_depth + 1 + v_desc_depth > 10 then
      raise exception 'container nesting too deep (max 10 levels)' using errcode = '23514';
    end if;
  end if;
  return new;
end
$$;

create trigger assets_before_write
  before insert or update on public.assets
  for each row execute function private.assets_before_write();

create trigger assets_touch before update on public.assets
  for each row execute function private.touch_updated_at();

-- ---------------------------------------------------------------------------
-- Tags: one lookup for barcodes and NFC UIDs
-- ---------------------------------------------------------------------------
create table public.tags (
  value text primary key,
  kind public.tag_kind not null,
  asset_id uuid references public.assets (id) on delete restrict,
  location_id uuid references public.locations (id) on delete restrict,
  retired_at timestamptz,
  created_at timestamptz not null default now(),
  constraint tags_exactly_one_target check (num_nonnulls(asset_id, location_id) = 1)
);
create index tags_asset_idx on public.tags (asset_id);
create index tags_location_idx on public.tags (location_id);

-- NFC: uppercase hex, no separators. Barcode: trimmed.
create function private.normalize_tag_value(p_kind public.tag_kind, p_value text) returns text
language plpgsql immutable set search_path = '' as $$
declare
  v text;
begin
  if p_kind = 'nfc' then
    v := upper(regexp_replace(coalesce(p_value, ''), '[\s:\-]', '', 'g'));
    if v !~ '^([0-9A-F]{2}){4,}$' then
      raise exception 'invalid NFC UID: expected at least 4 bytes of hex' using errcode = '22023';
    end if;
    return v;
  end if;
  v := btrim(coalesce(p_value, ''));
  if v = '' then
    raise exception 'tag value must not be empty' using errcode = '22023';
  end if;
  return v;
end
$$;

create function private.tags_before_write() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  new.value := private.normalize_tag_value(new.kind, new.value);
  return new;
end
$$;

create trigger tags_before_write
  before insert or update of value, kind on public.tags
  for each row execute function private.tags_before_write();

-- Every asset and location is scannable by its printed code.
create function private.assets_after_insert() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.tags (value, kind, asset_id) values (new.asset_code, 'barcode', new.id);
  return null;
end
$$;
create trigger assets_after_insert after insert on public.assets
  for each row execute function private.assets_after_insert();

create function private.locations_after_insert() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.tags (value, kind, location_id) values (new.code, 'barcode', new.id);
  return null;
end
$$;
create trigger locations_after_insert after insert on public.locations
  for each row execute function private.locations_after_insert();

-- ---------------------------------------------------------------------------
-- Photos, projects, checkouts
-- ---------------------------------------------------------------------------
create table public.asset_photos (
  id uuid primary key default gen_random_uuid(),
  asset_id uuid not null references public.assets (id) on delete cascade,
  storage_path text not null,
  created_by uuid references public.profiles (id) on delete set null,
  created_at timestamptz not null default now()
);
create index asset_photos_asset_idx on public.asset_photos (asset_id);

create table public.projects (
  id uuid primary key default gen_random_uuid(),
  code text not null unique check (btrim(code) <> ''),
  name text not null check (btrim(name) <> ''),
  starts_on date,
  ends_on date,
  created_at timestamptz not null default now(),
  constraint projects_dates check (starts_on is null or ends_on is null or ends_on >= starts_on)
);

create table public.checkouts (
  id uuid primary key default gen_random_uuid(),
  project_id uuid references public.projects (id) on delete restrict,
  reason text,
  due_at timestamptz,
  created_by uuid references public.profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  closed_at timestamptz,
  constraint checkouts_project_or_reason check (project_id is not null or nullif(btrim(reason), '') is not null)
);

create table public.checkout_items (
  id uuid primary key default gen_random_uuid(),
  checkout_id uuid not null references public.checkouts (id) on delete cascade,
  asset_id uuid not null references public.assets (id) on delete restrict,
  checked_out_at timestamptz not null default now(),
  returned_at timestamptz,
  returned_to_location_id uuid references public.locations (id) on delete set null
);
create unique index checkout_items_one_open_per_asset on public.checkout_items (asset_id) where returned_at is null;
create index checkout_items_checkout_idx on public.checkout_items (checkout_id);

-- ---------------------------------------------------------------------------
-- Sessions and events (append-only)
-- ---------------------------------------------------------------------------
create table public.scan_sessions (
  id uuid primary key,
  mode text not null default 'unknown',
  device_id text,
  user_id uuid,
  started_at timestamptz not null default now(),
  ended_at timestamptz
);

create table public.asset_events (
  id uuid primary key,                       -- client-generated: retries are harmless
  asset_id uuid not null references public.assets (id) on delete restrict,
  type public.event_type not null,
  session_id uuid references public.scan_sessions (id) on delete restrict,
  to_location_id uuid references public.locations (id) on delete restrict,
  to_asset_id uuid references public.assets (id) on delete restrict,
  checkout_id uuid references public.checkouts (id) on delete restrict,
  lat double precision,
  lng double precision,
  accuracy_m real,
  payload jsonb not null default '{}'::jsonb,
  occurred_at timestamptz not null,
  received_at timestamptz not null default now(),
  user_id uuid,                              -- no FK: the audit trail must survive user removal
  device_id text
);
create index asset_events_asset_idx on public.asset_events (asset_id, occurred_at desc);
create index asset_events_session_idx on public.asset_events (session_id);
create index asset_events_occurred_idx on public.asset_events (occurred_at desc);

create function private.asset_events_append_only() returns trigger
language plpgsql set search_path = '' as $$
begin
  raise exception 'asset_events is append-only' using errcode = '42501';
end
$$;
create trigger asset_events_no_update_delete before update or delete on public.asset_events
  for each row execute function private.asset_events_append_only();
create trigger asset_events_no_truncate before truncate on public.asset_events
  for each statement execute function private.asset_events_append_only();

-- ---------------------------------------------------------------------------
-- Audits
-- ---------------------------------------------------------------------------
create table public.audits (
  id uuid primary key default gen_random_uuid(),
  location_id uuid not null references public.locations (id) on delete restrict,
  started_by uuid references public.profiles (id) on delete set null,
  started_at timestamptz not null default now(),
  finished_at timestamptz
);

create table public.audit_lines (
  id uuid primary key default gen_random_uuid(),
  audit_id uuid not null references public.audits (id) on delete cascade,
  asset_id uuid not null references public.assets (id) on delete restrict,
  result public.audit_result not null,
  created_at timestamptz not null default now(),
  unique (audit_id, asset_id)
);

-- ---------------------------------------------------------------------------
-- Views
-- ---------------------------------------------------------------------------
-- Effective location is derived by walking up the container chain.
create view public.asset_effective_location with (security_invoker = true) as
with recursive chain as (
  select a.id as asset_id, a.parent_location_id, a.parent_asset_id, 0 as container_depth
  from public.assets a
  union all
  select c.asset_id, p.parent_location_id, p.parent_asset_id, c.container_depth + 1
  from chain c join public.assets p on p.id = c.parent_asset_id
  where c.container_depth < 10
)
select c.asset_id, c.parent_location_id as location_id, c.container_depth
from chain c
where c.parent_asset_id is null;

create view public.overdue_checkout_items with (security_invoker = true) as
select ci.id as checkout_item_id, ci.checkout_id, ci.asset_id, ci.checked_out_at,
       c.project_id, c.reason, c.due_at, c.created_by
from public.checkout_items ci
join public.checkouts c on c.id = ci.checkout_id
where ci.returned_at is null and c.due_at is not null and c.due_at < now();

-- Point lookup used by RPCs (the view above cannot push a filter into its recursion).
create function private.effective_location(p_asset uuid)
returns table (location_id uuid, container_depth int)
language sql stable security definer set search_path = '' as $$
  with recursive chain as (
    select a.parent_location_id, a.parent_asset_id, 0 as container_depth
    from public.assets a where a.id = p_asset
    union all
    select p.parent_location_id, p.parent_asset_id, c.container_depth + 1
    from chain c join public.assets p on p.id = c.parent_asset_id
    where c.container_depth < 10
  )
  select c.parent_location_id, c.container_depth from chain c where c.parent_asset_id is null
$$;

-- ---------------------------------------------------------------------------
-- Row level security
-- ---------------------------------------------------------------------------
alter table public.profiles enable row level security;
alter table public.categories enable row level security;
alter table public.asset_templates enable row level security;
alter table public.locations enable row level security;
alter table public.assets enable row level security;
alter table public.tags enable row level security;
alter table public.asset_photos enable row level security;
alter table public.projects enable row level security;
alter table public.checkouts enable row level security;
alter table public.checkout_items enable row level security;
alter table public.scan_sessions enable row level security;
alter table public.asset_events enable row level security;
alter table public.audits enable row level security;
alter table public.audit_lines enable row level security;

-- profiles
create policy profiles_select on public.profiles for select to authenticated
  using ((select private.is_member()));
create policy profiles_update_self on public.profiles for update to authenticated
  using (id = (select auth.uid()))
  with check (id = (select auth.uid()) and role = (select private.user_role()));
create policy profiles_admin_all on public.profiles for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));

-- categories / templates: everyone reads, admins manage
create policy categories_select on public.categories for select to authenticated
  using ((select private.is_member()));
create policy categories_admin on public.categories for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));
create policy templates_select on public.asset_templates for select to authenticated
  using ((select private.is_member()));
create policy templates_admin on public.asset_templates for all to authenticated
  using ((select private.is_admin())) with check ((select private.is_admin()));

-- locations / assets / tags: everyone reads, managers write (crew write through RPCs)
create policy locations_select on public.locations for select to authenticated
  using ((select private.is_member()));
create policy locations_insert on public.locations for insert to authenticated
  with check ((select private.is_manager_plus()));
create policy locations_update on public.locations for update to authenticated
  using ((select private.is_manager_plus())) with check ((select private.is_manager_plus()));
create policy locations_delete on public.locations for delete to authenticated
  using ((select private.is_admin()));

create policy assets_select on public.assets for select to authenticated
  using ((select private.is_member()));
create policy assets_insert on public.assets for insert to authenticated
  with check ((select private.is_manager_plus()));
create policy assets_update on public.assets for update to authenticated
  using ((select private.is_manager_plus())) with check ((select private.is_manager_plus()));
-- no delete policy: assets are retired (soft delete), never removed

create policy tags_select on public.tags for select to authenticated
  using ((select private.is_member()));
create policy tags_insert on public.tags for insert to authenticated
  with check ((select private.is_manager_plus()));
create policy tags_update on public.tags for update to authenticated
  using ((select private.is_manager_plus())) with check ((select private.is_manager_plus()));

-- photos: crew may add their own, managers may remove
create policy photos_select on public.asset_photos for select to authenticated
  using ((select private.is_member()));
create policy photos_insert on public.asset_photos for insert to authenticated
  with check ((select private.is_crew_plus()) and created_by = (select auth.uid()));
create policy photos_delete on public.asset_photos for delete to authenticated
  using ((select private.is_manager_plus()));

-- projects / checkouts
create policy projects_select on public.projects for select to authenticated
  using ((select private.is_member()));
create policy projects_manage on public.projects for all to authenticated
  using ((select private.is_manager_plus())) with check ((select private.is_manager_plus()));

create policy checkouts_select on public.checkouts for select to authenticated
  using ((select private.is_member()));
create policy checkouts_insert on public.checkouts for insert to authenticated
  with check ((select private.is_manager_plus()));
create policy checkouts_update on public.checkouts for update to authenticated
  using ((select private.is_manager_plus())) with check ((select private.is_manager_plus()));

create policy checkout_items_select on public.checkout_items for select to authenticated
  using ((select private.is_member()));
create policy checkout_items_insert on public.checkout_items for insert to authenticated
  with check ((select private.is_manager_plus()));
create policy checkout_items_update on public.checkout_items for update to authenticated
  using ((select private.is_manager_plus())) with check ((select private.is_manager_plus()));

-- sessions and events: read only; written exclusively by apply_scan_events
create policy sessions_select on public.scan_sessions for select to authenticated
  using (user_id = (select auth.uid()) or (select private.is_manager_plus()));
create policy events_select on public.asset_events for select to authenticated
  using ((select private.is_member()));

-- audits
create policy audits_select on public.audits for select to authenticated
  using ((select private.is_member()));
create policy audits_insert on public.audits for insert to authenticated
  with check ((select private.is_manager_plus()));
create policy audits_update on public.audits for update to authenticated
  using ((select private.is_manager_plus())) with check ((select private.is_manager_plus()));
create policy audit_lines_select on public.audit_lines for select to authenticated
  using ((select private.is_member()));
create policy audit_lines_insert on public.audit_lines for insert to authenticated
  with check ((select private.is_manager_plus()));
create policy audit_lines_update on public.audit_lines for update to authenticated
  using ((select private.is_manager_plus())) with check ((select private.is_manager_plus()));

-- ---------------------------------------------------------------------------
-- Grants (least privilege; RLS narrows further). anon gets nothing.
-- ---------------------------------------------------------------------------
revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;

grant select on all tables in schema public to authenticated;
grant insert, update on public.locations, public.assets, public.tags, public.checkouts,
  public.checkout_items, public.audits, public.audit_lines to authenticated;
grant insert, update, delete on public.projects, public.categories, public.asset_templates to authenticated;
grant update on public.profiles to authenticated;
grant insert, delete on public.asset_photos to authenticated;
grant delete on public.locations to authenticated;
grant usage on all sequences in schema public to authenticated;

grant all on all tables in schema public to service_role;
grant all on all sequences in schema public to service_role;

grant usage on schema private to authenticated, service_role;
grant execute on all functions in schema private to authenticated, service_role;
-- Only the role helpers and trigger-free lookups are needed by policies; everything else in
-- `private` is only ever invoked from trigger/definer context, so tighten it:
revoke execute on all functions in schema private from public, anon;
revoke execute on function private.generate_asset_code(boolean) from authenticated;
revoke execute on function private.normalize_tag_value(public.tag_kind, text) from authenticated;
revoke execute on function private.effective_location(uuid) from authenticated;
