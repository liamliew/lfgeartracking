-- get_asset_detail: everything the Asset Detail screen shows, in one call.
-- SECURITY DEFINER with an explicit membership check (same pattern as resolve_tag).

create function public.get_asset_detail(p_asset uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  a public.assets%rowtype;
  v_loc record;
  v_location jsonb;
  v_chain jsonb;
  v_checkout jsonb;
  v_tags jsonb;
  v_photos jsonb;
  v_events jsonb;
  v_category jsonb;
  v_home jsonb;
  v_last_seen jsonb;
begin
  if not private.is_member() then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  select * into a from public.assets x where x.id = p_asset;
  if not found then
    return jsonb_build_object('found', false);
  end if;

  select el.location_id, el.container_depth into v_loc from private.effective_location(a.id) el;
  if v_loc.location_id is not null then
    select jsonb_build_object('id', l.id, 'code', l.code, 'name', l.name, 'path', l.path_cache)
    into v_location from public.locations l where l.id = v_loc.location_id;
  end if;

  -- containers above this asset, outermost first
  with recursive up as (
    select p.id, p.asset_code, p.name, p.parent_asset_id, 1 as depth from public.assets p where p.id = a.parent_asset_id
    union all
    select p.id, p.asset_code, p.name, p.parent_asset_id, u.depth + 1
    from public.assets p join up u on p.id = u.parent_asset_id where u.depth < 50
  )
  select coalesce(jsonb_agg(jsonb_build_object('id', up.id, 'code', up.asset_code, 'name', up.name) order by up.depth desc), '[]'::jsonb)
  into v_chain from up;

  select jsonb_build_object('id', c.id, 'name', c.name) into v_category from public.categories c where c.id = a.category_id;
  select jsonb_build_object('id', l.id, 'path', l.path_cache) into v_home from public.locations l where l.id = a.home_location_id;

  if a.last_seen_at is not null then
    select jsonb_build_object(
             'at', a.last_seen_at, 'by', p.display_name,
             'lat', a.last_seen_lat, 'lng', a.last_seen_lng, 'accuracy_m', a.last_seen_accuracy_m)
    into v_last_seen from (select 1) s left join public.profiles p on p.id = a.last_seen_by;
  end if;

  select jsonb_build_object(
           'checkout_id', c.id, 'project_id', c.project_id, 'project_name', pr.name,
           'reason', c.reason, 'due_at', c.due_at, 'checked_out_at', ci.checked_out_at)
  into v_checkout
  from public.checkout_items ci
  join public.checkouts c on c.id = ci.checkout_id
  left join public.projects pr on pr.id = c.project_id
  where ci.asset_id = a.id and ci.returned_at is null;

  select coalesce(jsonb_agg(jsonb_build_object('value', t.value, 'kind', t.kind, 'retired', t.retired_at is not null)
                            order by (t.value = a.asset_code) desc, t.created_at, t.value), '[]'::jsonb)
  into v_tags from public.tags t where t.asset_id = a.id;

  select coalesce(jsonb_agg(jsonb_build_object('id', ph.id, 'storage_path', ph.storage_path) order by ph.created_at), '[]'::jsonb)
  into v_photos from public.asset_photos ph where ph.asset_id = a.id;

  select coalesce(jsonb_agg(e.j order by e.occurred_at desc), '[]'::jsonb)
  into v_events
  from (
    select ev.occurred_at,
           jsonb_build_object(
             'id', ev.id, 'type', ev.type, 'occurred_at', ev.occurred_at,
             'user_name', p.display_name,
             'to_location', case when l.id is null then null else jsonb_build_object('id', l.id, 'path', l.path_cache) end,
             'to_asset', case when ta.id is null then null else jsonb_build_object('id', ta.id, 'code', ta.asset_code, 'name', ta.name) end,
             'payload', ev.payload) as j
    from public.asset_events ev
    left join public.profiles p on p.id = ev.user_id
    left join public.locations l on l.id = ev.to_location_id
    left join public.assets ta on ta.id = ev.to_asset_id
    where ev.asset_id = a.id
    order by ev.occurred_at desc
    limit 50
  ) e;

  return jsonb_build_object(
    'found', true,
    'asset', jsonb_build_object(
      'id', a.id, 'code', a.asset_code, 'name', a.name, 'status', a.status, 'is_container', a.is_container,
      'manufacturer', a.manufacturer, 'model', a.model, 'serial_number', a.serial_number, 'notes', a.notes,
      'custom', a.custom, 'retired_at', a.retired_at, 'category', v_category, 'home_location', v_home),
    'location', v_location,
    'container_chain', v_chain,
    'last_seen', v_last_seen,
    'open_checkout', v_checkout,
    'tags', v_tags,
    'photos', v_photos,
    'events', v_events);
end
$$;

revoke execute on function public.get_asset_detail(uuid) from public, anon;
grant execute on function public.get_asset_detail(uuid) to authenticated;
grant execute on function public.get_asset_detail(uuid) to service_role;
