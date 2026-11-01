-- Gear Tracker RPCs: resolve_tag, apply_scan_events, create_asset, search_assets.
-- All are SECURITY DEFINER with an empty search_path (except search_assets, which is
-- SECURITY INVOKER so RLS applies). Crew devices write only through these functions.
-- Docs consulted: https://supabase.com/docs/guides/database/functions

create function private.try_timestamptz(p text) returns timestamptz
language plpgsql stable set search_path = '' as $$
begin
  return p::timestamptz;
exception when others then
  return null;
end
$$;

-- ---------------------------------------------------------------------------
-- resolve_tag: one lookup for barcodes and NFC UIDs
-- ---------------------------------------------------------------------------
create function public.resolve_tag(p_value text) returns jsonb
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
begin
  if not private.is_member() then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  if v = '' then
    return jsonb_build_object('kind', 'unknown', 'value', v);
  end if;

  select * into t from public.tags x where x.value = v and x.retired_at is null;
  if not found and v ~ '^([0-9A-Fa-f]{2}[-: ]){3,}[0-9A-Fa-f]{2}$' then
    -- legacy NFC ids were stored as dash separated hex pairs
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
    return jsonb_build_object(
      'kind', 'location', 'tag', t.value, 'tag_kind', t.kind,
      'id', l.id, 'code', l.code, 'name', l.name, 'location_kind', l.kind,
      'path', l.path_cache, 'archived', l.archived_at is not null);
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
    'open_checkout', v_checkout);
end
$$;

-- ---------------------------------------------------------------------------
-- apply_scan_events: idempotent, ordered, per-event accept/reject
-- ---------------------------------------------------------------------------
create function private.apply_one_event(e jsonb, p_uid uuid, p_role public.app_role) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_id uuid;
  v_type public.event_type;
  v_occurred timestamptz;
  v_asset public.assets%rowtype;
  v_to_loc uuid;
  v_to_asset uuid;
  v_checkout uuid;
  v_session uuid;
  v_payload jsonb;
  v_lat double precision;
  v_lng double precision;
  v_acc real;
  v_later_placement boolean;
  v_note text;
  v_line public.checkout_items%rowtype;
  v_new_status public.asset_status;
  v_tag jsonb;
  v_patch jsonb;
begin
  if jsonb_typeof(e) is distinct from 'object' then
    raise exception 'event must be a json object' using errcode = '22023';
  end if;
  v_id := (e ->> 'id')::uuid;
  if v_id is null then
    raise exception 'event id is required' using errcode = '22023';
  end if;
  if exists (select 1 from public.asset_events ev where ev.id = v_id) then
    return jsonb_build_object('id', v_id, 'status', 'duplicate');
  end if;

  v_type := (e ->> 'type')::public.event_type;
  if v_type is null then
    raise exception 'event type is required' using errcode = '22023';
  end if;
  if e ->> 'occurred_at' is null then
    raise exception 'occurred_at is required' using errcode = '22023';
  end if;
  v_occurred := (e ->> 'occurred_at')::timestamptz;
  if v_occurred > now() + interval '1 day' then
    raise exception 'occurred_at is in the future' using errcode = '22023';
  end if;

  v_to_loc := (e ->> 'to_location_id')::uuid;
  v_to_asset := (e ->> 'to_asset_id')::uuid;
  v_checkout := (e ->> 'checkout_id')::uuid;
  v_session := (e ->> 'session_id')::uuid;
  v_payload := coalesce(e -> 'payload', '{}'::jsonb);
  if jsonb_typeof(v_payload) is distinct from 'object' then
    raise exception 'payload must be a json object' using errcode = '22023';
  end if;
  v_lat := (e ->> 'lat')::double precision;
  v_lng := (e ->> 'lng')::double precision;
  v_acc := (e ->> 'accuracy_m')::real;

  select * into v_asset from public.assets a where a.id = (e ->> 'asset_id')::uuid for update;
  if not found then
    raise exception 'asset not found' using errcode = 'P0002';
  end if;
  if v_type = 'created' then
    raise exception 'created events are produced by create_asset only' using errcode = '22023';
  end if;
  if v_asset.retired_at is not null
     and v_type not in ('seen', 'audited_found', 'audited_missing', 'status_changed') then
    raise exception 'asset is retired' using errcode = '23514';
  end if;
  if v_to_loc is not null
     and not exists (select 1 from public.locations l where l.id = v_to_loc and l.archived_at is null) then
    raise exception 'location not found or archived' using errcode = 'P0002';
  end if;

  -- A newer placement already applied means this (older, late-arriving) event is history only.
  v_later_placement := exists (
    select 1 from public.asset_events ev
    where ev.asset_id = v_asset.id and ev.occurred_at > v_occurred
      and (ev.type in ('placed_in_location', 'placed_in_container', 'removed_from_container')
           or (ev.type = 'checked_in' and ev.to_location_id is not null)));

  case v_type
    when 'placed_in_location' then
      if v_to_loc is null then
        raise exception 'to_location_id is required' using errcode = '22023';
      end if;
      if v_later_placement then
        v_note := 'superseded';
      else
        update public.assets set parent_location_id = v_to_loc, parent_asset_id = null
        where id = v_asset.id;
      end if;

    when 'placed_in_container' then
      if v_to_asset is null then
        raise exception 'to_asset_id is required' using errcode = '22023';
      end if;
      if v_later_placement then
        v_note := 'superseded';
      else
        update public.assets set parent_asset_id = v_to_asset, parent_location_id = null
        where id = v_asset.id;
      end if;

    when 'removed_from_container' then
      if v_later_placement then
        v_note := 'superseded';
      else
        update public.assets set parent_asset_id = null, parent_location_id = v_to_loc
        where id = v_asset.id;
      end if;

    when 'seen' then
      if v_asset.last_seen_at is null or v_asset.last_seen_at <= v_occurred then
        update public.assets
        set last_seen_at = v_occurred, last_seen_by = p_uid, last_seen_lat = v_lat,
            last_seen_lng = v_lng, last_seen_accuracy_m = v_acc
        where id = v_asset.id;
        if v_asset.is_container then
          -- contents are "seen via container"
          with recursive d as (
            select a.id from public.assets a where a.parent_asset_id = v_asset.id
            union all
            select a.id from public.assets a join d on a.parent_asset_id = d.id
          )
          update public.assets s
          set last_seen_at = v_occurred, last_seen_by = p_uid, last_seen_lat = v_lat,
              last_seen_lng = v_lng, last_seen_accuracy_m = v_acc
          from d
          where s.id = d.id and (s.last_seen_at is null or s.last_seen_at <= v_occurred);
        end if;
      else
        v_note := 'superseded';
      end if;

    when 'checked_out' then
      if v_asset.status in ('in_repair', 'lost', 'retired') then
        raise exception 'asset is % and cannot be checked out', v_asset.status using errcode = '23514';
      end if;
      if v_checkout is null then
        raise exception 'checkout_id is required' using errcode = '22023';
      end if;
      select * into v_line from public.checkout_items i
      where i.asset_id = v_asset.id and i.returned_at is null for update;
      if found then
        if v_line.checkout_id = v_checkout then
          v_note := 'already_in_checkout';
        elsif coalesce((v_payload ->> 'transfer')::boolean, false) then
          update public.checkout_items set returned_at = v_occurred where id = v_line.id;
          update public.checkouts c set closed_at = v_occurred
          where c.id = v_line.checkout_id and c.closed_at is null
            and not exists (select 1 from public.checkout_items i
                            where i.checkout_id = c.id and i.returned_at is null);
        else
          raise exception 'asset is already checked out (set payload.transfer to move it)'
            using errcode = '23505';
        end if;
      end if;
      if v_note is null then
        if not exists (select 1 from public.checkouts c where c.id = v_checkout) then
          insert into public.checkouts (id, project_id, reason, due_at, created_by, created_at)
          values (v_checkout, nullif(v_payload ->> 'project_id', '')::uuid,
                  nullif(btrim(v_payload ->> 'reason'), ''),
                  (v_payload ->> 'due_at')::timestamptz, p_uid, v_occurred);
        elsif exists (select 1 from public.checkouts c where c.id = v_checkout and c.closed_at is not null) then
          raise exception 'checkout is already closed' using errcode = '23514';
        end if;
        insert into public.checkout_items (checkout_id, asset_id, checked_out_at)
        values (v_checkout, v_asset.id, v_occurred);
        update public.assets set status = 'checked_out' where id = v_asset.id;
      end if;

    when 'checked_in' then
      select * into v_line from public.checkout_items i
      where i.asset_id = v_asset.id and i.returned_at is null for update;
      if found then
        update public.checkout_items
        set returned_at = v_occurred, returned_to_location_id = v_to_loc
        where id = v_line.id;
        update public.checkouts c set closed_at = v_occurred
        where c.id = v_line.checkout_id and c.closed_at is null
          and not exists (select 1 from public.checkout_items i
                          where i.checkout_id = c.id and i.returned_at is null);
      else
        -- anomaly: never block scanning; accept and flag
        v_note := 'not_checked_out';
      end if;
      update public.assets set status = 'available'
      where id = v_asset.id and status = 'checked_out';
      if v_to_loc is not null then
        if v_later_placement then
          v_note := coalesce(v_note, 'placement_superseded');
        else
          update public.assets set parent_location_id = v_to_loc, parent_asset_id = null
          where id = v_asset.id;
        end if;
      end if;

    when 'status_changed' then
      v_new_status := (v_payload ->> 'status')::public.asset_status;
      if v_new_status is null then
        raise exception 'payload.status is required' using errcode = '22023';
      end if;
      if v_new_status = 'checked_out' then
        raise exception 'use a checked_out event to check an asset out' using errcode = '22023';
      end if;
      if p_role = 'crew' and v_new_status not in ('available', 'in_repair') then
        raise exception 'insufficient privilege to set status %', v_new_status using errcode = '42501';
      end if;
      if v_asset.status = 'checked_out' and v_new_status <> 'lost' then
        raise exception 'asset is checked out; check it in first' using errcode = '23514';
      end if;
      if exists (select 1 from public.asset_events ev
                 where ev.asset_id = v_asset.id and ev.type = 'status_changed' and ev.occurred_at > v_occurred) then
        v_note := 'superseded';
      else
        update public.assets
        set status = v_new_status,
            retired_at = case when v_new_status = 'retired' then v_occurred else null end
        where id = v_asset.id;
      end if;

    when 'tag_assigned' then
      v_tag := v_payload -> 'tag';
      if jsonb_typeof(v_tag) is distinct from 'object' then
        raise exception 'payload.tag {value, kind} is required' using errcode = '22023';
      end if;
      begin
        insert into public.tags (value, kind, asset_id)
        values (v_tag ->> 'value', coalesce((v_tag ->> 'kind')::public.tag_kind, 'barcode'), v_asset.id);
      exception when unique_violation then
        raise exception 'tag_in_use' using errcode = '23505';
      end;

    when 'edited' then
      if p_role not in ('manager', 'admin') then
        raise exception 'insufficient privilege to edit assets' using errcode = '42501';
      end if;
      v_patch := v_payload -> 'patch';
      if jsonb_typeof(v_patch) is distinct from 'object' then
        raise exception 'payload.patch is required' using errcode = '22023';
      end if;
      update public.assets set
        name = case when v_patch ? 'name' then v_patch ->> 'name' else name end,
        notes = case when v_patch ? 'notes' then v_patch ->> 'notes' else notes end,
        manufacturer = case when v_patch ? 'manufacturer' then v_patch ->> 'manufacturer' else manufacturer end,
        model = case when v_patch ? 'model' then v_patch ->> 'model' else model end,
        serial_number = case when v_patch ? 'serial_number' then v_patch ->> 'serial_number' else serial_number end,
        category_id = case when v_patch ? 'category_id' then (v_patch ->> 'category_id')::uuid else category_id end,
        home_location_id = case when v_patch ? 'home_location_id' then (v_patch ->> 'home_location_id')::uuid else home_location_id end,
        custom = case when jsonb_typeof(v_patch -> 'custom') = 'object' then custom || (v_patch -> 'custom') else custom end
      where id = v_asset.id;

    when 'audited_found', 'audited_missing' then
      null; -- history only; managers confirm "lost" on the web

    else
      raise exception 'unsupported event type %', v_type using errcode = '22023';
  end case;

  if v_session is not null then
    insert into public.scan_sessions (id, mode, device_id, user_id, started_at)
    values (v_session, coalesce(nullif(e ->> 'session_mode', ''), 'unknown'), e ->> 'device_id', p_uid, v_occurred)
    on conflict (id) do nothing;
  end if;

  insert into public.asset_events
    (id, asset_id, type, session_id, to_location_id, to_asset_id, checkout_id,
     lat, lng, accuracy_m, payload, occurred_at, user_id, device_id)
  values
    (v_id, v_asset.id, v_type, v_session, v_to_loc, v_to_asset, v_checkout,
     v_lat, v_lng, v_acc, v_payload, v_occurred, p_uid, e ->> 'device_id');

  return jsonb_build_object('id', v_id, 'status', 'accepted')
         || case when v_note is not null then jsonb_build_object('reason', v_note) else '{}'::jsonb end;
end
$$;

create function public.apply_scan_events(p_events jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_role public.app_role := private.user_role();
  v_results jsonb := '[]'::jsonb;
  v_result jsonb;
  r record;
begin
  if v_uid is null or v_role is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  if v_role not in ('crew', 'manager', 'admin') then
    raise exception 'insufficient privilege to record scan events' using errcode = '42501';
  end if;
  if jsonb_typeof(p_events) is distinct from 'array' then
    raise exception 'p_events must be a json array' using errcode = '22023';
  end if;
  if jsonb_array_length(p_events) > 500 then
    raise exception 'too many events in one batch (max 500)' using errcode = '22023';
  end if;

  for r in
    select t.e, t.ord
    from jsonb_array_elements(p_events) with ordinality as t(e, ord)
    order by private.try_timestamptz(t.e ->> 'occurred_at') nulls last, t.ord
  loop
    begin
      v_result := private.apply_one_event(r.e, v_uid, v_role);
    exception when others then
      v_result := jsonb_build_object(
        'id', r.e ->> 'id', 'status', 'rejected', 'reason', sqlerrm, 'code', sqlstate);
    end;
    v_results := v_results || jsonb_build_array(v_result);
  end loop;
  return v_results;
end
$$;

-- ---------------------------------------------------------------------------
-- create_asset: asset + tag + first placement, atomically and idempotently
-- ---------------------------------------------------------------------------
create function public.create_asset(p jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_role public.app_role := private.user_role();
  v_id uuid;
  v_name text := btrim(coalesce(p ->> 'name', ''));
  v_occurred timestamptz := coalesce((p ->> 'occurred_at')::timestamptz, now());
  v_device text := p ->> 'device_id';
  v_tag_value text;
  v_tag_kind public.tag_kind;
  v_code text := nullif(btrim(coalesce(p ->> 'asset_code', '')), '');
  v_is_container boolean;
  v_loc uuid := (p ->> 'location_id')::uuid;
  v_cat uuid := (p ->> 'category_id')::uuid;
  v_existing record;
  v_asset public.assets%rowtype;
begin
  if v_uid is null or v_role is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  if v_role not in ('crew', 'manager', 'admin') then
    raise exception 'insufficient privilege to create assets' using errcode = '42501';
  end if;
  if jsonb_typeof(p) is distinct from 'object' then
    raise exception 'argument must be a json object' using errcode = '22023';
  end if;

  v_id := coalesce((p ->> 'id')::uuid, gen_random_uuid());
  select * into v_asset from public.assets a where a.id = v_id;
  if found then
    return jsonb_build_object('status', 'duplicate', 'asset', to_jsonb(v_asset));
  end if;

  if v_name = '' then
    raise exception 'name is required' using errcode = '22023';
  end if;
  if v_loc is not null
     and not exists (select 1 from public.locations l where l.id = v_loc and l.archived_at is null) then
    return jsonb_build_object('status', 'rejected', 'reason', 'location_not_found');
  end if;

  if jsonb_typeof(p -> 'tag') = 'object' then
    v_tag_kind := coalesce((p -> 'tag' ->> 'kind')::public.tag_kind, 'barcode');
    v_tag_value := private.normalize_tag_value(v_tag_kind, p -> 'tag' ->> 'value');
    select t.asset_id, t.location_id into v_existing from public.tags t where t.value = v_tag_value;
    if found then
      return jsonb_build_object('status', 'rejected', 'reason', 'tag_in_use',
                                'existing', public.resolve_tag(v_tag_value));
    end if;
    -- a pre-printed label like A000200 becomes the asset's own code
    if v_code is null and v_tag_kind = 'barcode' and v_tag_value ~ '^[AC][0-9]{6}$' then
      v_code := v_tag_value;
    end if;
  end if;

  if v_code is not null
     and (exists (select 1 from public.assets a where a.asset_code = v_code)
          or (exists (select 1 from public.tags t where t.value = v_code) and v_code is distinct from v_tag_value)) then
    return jsonb_build_object('status', 'rejected', 'reason', 'asset_code_in_use');
  end if;

  v_is_container := coalesce(
    (p ->> 'is_container')::boolean,
    (select c.default_is_container from public.categories c where c.id = v_cat),
    false);

  insert into public.assets
    (id, asset_code, name, category_id, manufacturer, model, serial_number, is_container,
     parent_location_id, home_location_id, custom, notes, created_by)
  values
    (v_id, v_code, v_name, v_cat, nullif(btrim(p ->> 'manufacturer'), ''), nullif(btrim(p ->> 'model'), ''),
     nullif(btrim(p ->> 'serial_number'), ''), v_is_container, v_loc,
     coalesce((p ->> 'home_location_id')::uuid, v_loc),
     coalesce(p -> 'custom', '{}'::jsonb), nullif(btrim(p ->> 'notes'), ''), v_uid)
  returning * into v_asset;

  insert into public.asset_events (id, asset_id, type, payload, occurred_at, user_id, device_id)
  values (gen_random_uuid(), v_asset.id, 'created',
          jsonb_build_object('name', v_asset.name, 'asset_code', v_asset.asset_code, 'source', 'create_asset'),
          v_occurred, v_uid, v_device);

  -- the printed code tag is created by trigger; add the scanned/tapped tag when it differs
  if v_tag_value is not null and not exists (select 1 from public.tags t where t.value = v_tag_value) then
    insert into public.tags (value, kind, asset_id) values (v_tag_value, v_tag_kind, v_asset.id);
    insert into public.asset_events (id, asset_id, type, payload, occurred_at, user_id, device_id)
    values (gen_random_uuid(), v_asset.id, 'tag_assigned',
            jsonb_build_object('tag', jsonb_build_object('value', v_tag_value, 'kind', v_tag_kind)),
            v_occurred, v_uid, v_device);
  end if;

  if v_loc is not null then
    insert into public.asset_events (id, asset_id, type, to_location_id, payload, occurred_at, user_id, device_id)
    values (gen_random_uuid(), v_asset.id, 'placed_in_location', v_loc, '{}'::jsonb, v_occurred, v_uid, v_device);
  end if;

  return jsonb_build_object('status', 'created', 'asset_code', v_asset.asset_code, 'asset', to_jsonb(v_asset));
end
$$;

-- ---------------------------------------------------------------------------
-- search_assets: exact tag/code, full text, trigram, substring (SECURITY INVOKER: RLS applies)
-- ---------------------------------------------------------------------------
create function public.search_assets(p_query text, p_limit int default 50) returns setof public.assets
language sql stable set search_path = '' as $$
  with q as (
    select btrim(coalesce(p_query, '')) as raw,
           case when btrim(coalesce(p_query, '')) ~ '^([0-9A-Fa-f]{2}[-: ]){3,}[0-9A-Fa-f]{2}$'
                then upper(regexp_replace(btrim(p_query), '[\s:\-]', '', 'g')) end as nfc
  )
  select a.*
  from public.assets a, q
  where q.raw <> '' and a.retired_at is null
    and (
      a.asset_code = q.raw
      or exists (select 1 from public.tags t
                 where t.asset_id = a.id and t.retired_at is null
                   and t.value in (q.raw, upper(q.raw), coalesce(q.nfc, q.raw)))
      or a.search @@ websearch_to_tsquery('simple', q.raw)
      or a.name operator(extensions.%) q.raw
      or a.serial_number operator(extensions.%) q.raw
      or position(lower(q.raw) in lower(a.name)) > 0
    )
  order by (a.asset_code = q.raw) desc,
           greatest(ts_rank(a.search, websearch_to_tsquery('simple', q.raw)),
                    extensions.similarity(a.name, q.raw)) desc,
           a.name
  limit least(greatest(coalesce(p_limit, 50), 1), 200)
$$;

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
revoke execute on function private.try_timestamptz(text) from public, anon, authenticated;
revoke execute on function private.apply_one_event(jsonb, uuid, public.app_role) from public, anon, authenticated;

revoke execute on function public.resolve_tag(text) from public, anon;
revoke execute on function public.apply_scan_events(jsonb) from public, anon;
revoke execute on function public.create_asset(jsonb) from public, anon;
revoke execute on function public.search_assets(text, int) from public, anon;
grant execute on function public.resolve_tag(text) to authenticated;
grant execute on function public.apply_scan_events(jsonb) to authenticated;
grant execute on function public.create_asset(jsonb) to authenticated;
grant execute on function public.search_assets(text, int) to authenticated;
grant execute on all functions in schema public to service_role;
