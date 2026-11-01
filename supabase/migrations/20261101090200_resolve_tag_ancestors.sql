-- resolve_tag now also returns what an offline-capable device needs for instant cycle checks and undo:
--   * placement:    the direct parent {location_id, container_id} (for compensating "undo" events)
--   * ancestor_ids: every container above an asset/container, or every location above a location
-- Replaces the function from 20261101090100_rpcs.sql (same signature, same grants).

create or replace function public.resolve_tag(p_value text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v text := btrim(coalesce(p_value, ''));
  t public.tags%rowtype;
  a public.assets%rowtype;
  l public.locations%rowtype;
  v_loc record;
  v_location jsonb;
  v_container jsonb;
  v_checkout jsonb;
  v_ancestors jsonb;
begin
  if not private.is_member() then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  if v = '' then
    return jsonb_build_object('kind', 'unknown', 'value', v);
  end if;

  select * into t from public.tags x where x.value = v and x.retired_at is null;
  if not found and v ~ '^([0-9A-Fa-f]{2}[-: ]){3,}[0-9A-Fa-f]{2}$' then
    select * into t from public.tags x
    where x.value = upper(regexp_replace(v, '[\s:\-]', '', 'g')) and x.retired_at is null;
  end if;
  if not found and v ~ '^([0-9A-Fa-f]{2}){4,}$' then
    select * into t from public.tags x where x.value = upper(v) and x.retired_at is null;
  end if;
  if not found then
    return jsonb_build_object('kind', 'unknown', 'value', v);
  end if;

  if t.location_id is not null then
    select * into l from public.locations x where x.id = t.location_id;
    with recursive up as (
      select p.id, p.parent_id, 1 as depth from public.locations p where p.id = l.parent_id
      union all
      select p.id, p.parent_id, u.depth + 1 from public.locations p join up u on p.id = u.parent_id where u.depth < 50
    )
    select coalesce(jsonb_agg(up.id order by up.depth), '[]'::jsonb) into v_ancestors from up;
    return jsonb_build_object(
      'kind', 'location', 'tag', t.value, 'tag_kind', t.kind,
      'id', l.id, 'code', l.code, 'name', l.name, 'location_kind', l.kind,
      'path', l.path_cache, 'archived', l.archived_at is not null,
      'ancestor_ids', v_ancestors,
      'placement', jsonb_build_object('location_id', l.parent_id, 'container_id', null));
  end if;

  select * into a from public.assets x where x.id = t.asset_id;

  select el.location_id, el.container_depth into v_loc from private.effective_location(a.id) el;
  if v_loc.location_id is not null then
    select jsonb_build_object('id', x.id, 'code', x.code, 'name', x.name, 'path', x.path_cache)
    into v_location from public.locations x where x.id = v_loc.location_id;
  end if;

  if a.parent_asset_id is not null then
    select jsonb_build_object('id', x.id, 'code', x.asset_code, 'name', x.name)
    into v_container from public.assets x where x.id = a.parent_asset_id;
  end if;

  with recursive up as (
    select p.id, p.parent_asset_id, 1 as depth from public.assets p where p.id = a.parent_asset_id
    union all
    select p.id, p.parent_asset_id, u.depth + 1 from public.assets p join up u on p.id = u.parent_asset_id where u.depth < 50
  )
  select coalesce(jsonb_agg(up.id order by up.depth), '[]'::jsonb) into v_ancestors from up;

  select jsonb_build_object(
           'checkout_id', c.id, 'project_id', c.project_id, 'project_name', p.name,
           'reason', c.reason, 'due_at', c.due_at, 'checked_out_at', ci.checked_out_at)
  into v_checkout
  from public.checkout_items ci
  join public.checkouts c on c.id = ci.checkout_id
  left join public.projects p on p.id = c.project_id
  where ci.asset_id = a.id and ci.returned_at is null;

  return jsonb_build_object(
    'kind', case when a.is_container then 'container' else 'asset' end,
    'tag', t.value, 'tag_kind', t.kind,
    'id', a.id, 'code', a.asset_code, 'name', a.name, 'status', a.status,
    'retired', a.retired_at is not null,
    'location', v_location, 'container', v_container,
    'container_depth', v_loc.container_depth,
    'open_checkout', v_checkout,
    'ancestor_ids', v_ancestors,
    'placement', jsonb_build_object('location_id', a.parent_location_id, 'container_id', a.parent_asset_id));
end
$$;
