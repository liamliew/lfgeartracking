-- Development seed data (runs after migrations on `supabase db reset`).
-- 3 sites, a room > shelf > cabinet > bin hierarchy, 5 categories, 60 assets (6 containers, one nested),
-- barcode + NFC tags, 2 projects and 1 overdue checkout. No users: create them via Studio / the Auth API.

-- Categories ------------------------------------------------------------------------------------
insert into public.categories (name, default_is_container, field_schema) values
  ('Cameras', false, '{"fields":[{"key":"sensor","label":"Sensor","type":"text","required":false},{"key":"shutter_count","label":"Shutter count","type":"number","required":false}]}'),
  ('Lenses', false, '{"fields":[{"key":"focal_length","label":"Focal length","type":"text","required":true},{"key":"mount","label":"Mount","type":"text","required":true}]}'),
  ('Audio', false, '{"fields":[{"key":"connector","label":"Connector","type":"text","required":false}]}'),
  ('Lighting', false, '{"fields":[{"key":"wattage","label":"Wattage","type":"number","required":false}]}'),
  ('Cases & Bags', true, '{}');

-- Locations -------------------------------------------------------------------------------------
insert into public.locations (id, name, kind, parent_id) values
  ('00000000-0000-4000-8000-000000000001', 'Savanna', 'site', null),
  ('00000000-0000-4000-8000-000000000002', 'YF House', 'site', null),
  ('00000000-0000-4000-8000-000000000003', 'Studio HQ', 'site', null);

insert into public.locations (id, name, kind, parent_id) values
  ('00000000-0000-4000-8000-000000000011', 'Storage 1', 'room', '00000000-0000-4000-8000-000000000001'),
  ('00000000-0000-4000-8000-000000000012', 'Storage 2', 'room', '00000000-0000-4000-8000-000000000001'),
  ('00000000-0000-4000-8000-000000000013', 'Edit Suite', 'room', '00000000-0000-4000-8000-000000000003');

insert into public.locations (id, name, kind, parent_id) values
  ('00000000-0000-4000-8000-000000000021', 'Shelf A', 'shelf', '00000000-0000-4000-8000-000000000011'),
  ('00000000-0000-4000-8000-000000000022', 'Shelf B', 'shelf', '00000000-0000-4000-8000-000000000011');

insert into public.locations (id, name, kind, parent_id) values
  ('00000000-0000-4000-8000-000000000031', 'Cabinet 1', 'cabinet', '00000000-0000-4000-8000-000000000021'),
  ('00000000-0000-4000-8000-000000000032', 'Cabinet 2', 'cabinet', '00000000-0000-4000-8000-000000000021');

insert into public.locations (id, name, kind, parent_id) values
  ('00000000-0000-4000-8000-000000000041', 'Bin 1A', 'bin', '00000000-0000-4000-8000-000000000031'),
  ('00000000-0000-4000-8000-000000000042', 'Bin 1B', 'bin', '00000000-0000-4000-8000-000000000031'),
  ('00000000-0000-4000-8000-000000000043', 'Bin 2A', 'bin', '00000000-0000-4000-8000-000000000032');

-- Projects --------------------------------------------------------------------------------------
insert into public.projects (id, code, name, starts_on, ends_on) values
  ('00000000-0000-4000-8000-0000000000a1', 'PRJ-001', 'Brand Shoot Q4', current_date - 14, current_date - 7),
  ('00000000-0000-4000-8000-0000000000a2', 'PRJ-002', 'Conference Livestream', current_date + 10, current_date + 12);

-- Assets ----------------------------------------------------------------------------------------
-- 6 containers (C-codes). Container 6 is nested inside container 1 (a pouch inside a case).
insert into public.assets (id, name, category_id, is_container, parent_location_id)
select ('00000000-0000-4000-8000-0000000001' || lpad(n::text, 2, '0'))::uuid,
       (array['Pelican 1510 Case','Pelican 1620 Case','Camera Bag A','Camera Bag B','Hard Case 1','Cable Pouch'])[n],
       (select id from public.categories where name = 'Cases & Bags'),
       true,
       case n when 6 then null
              else (array[
                '00000000-0000-4000-8000-000000000041',
                '00000000-0000-4000-8000-000000000042',
                '00000000-0000-4000-8000-000000000043',
                '00000000-0000-4000-8000-000000000011',
                '00000000-0000-4000-8000-000000000012',
                null])[n]::uuid end
from generate_series(1, 6) n;

update public.assets
set parent_asset_id = '00000000-0000-4000-8000-000000000101', parent_location_id = null
where id = '00000000-0000-4000-8000-000000000106';

-- 54 regular assets: cameras, lenses, audio, lighting. Every 4th lives in a container, rest on shelves/bins.
insert into public.assets (id, name, category_id, manufacturer, model, serial_number, parent_location_id, parent_asset_id, home_location_id)
select ('00000000-0000-4000-8000-0000000002' || lpad(n::text, 2, '0'))::uuid,
       c.label || ' #' || n,
       (select id from public.categories where name = c.cat),
       c.maker, c.model,
       case when n % 9 = 0 then 'SN' || lpad((n * 7919)::text, 8, '0') end,
       case when n % 4 = 0 then null
            else (array[
              '00000000-0000-4000-8000-000000000041','00000000-0000-4000-8000-000000000042',
              '00000000-0000-4000-8000-000000000043','00000000-0000-4000-8000-000000000022',
              '00000000-0000-4000-8000-000000000012','00000000-0000-4000-8000-000000000013'])[1 + n % 6]::uuid end,
       case when n % 4 = 0 then ('00000000-0000-4000-8000-0000000001' || lpad((1 + (n / 4) % 5)::text, 2, '0'))::uuid end,
       '00000000-0000-4000-8000-000000000001'
from generate_series(1, 54) n
cross join lateral (
  select * from (values
    (0, 'Cameras', 'Sony FX3', 'Sony', 'ILME-FX3'),
    (1, 'Lenses', '24-70mm f2.8', 'Sigma', 'Art 24-70'),
    (2, 'Audio', 'SM58 Mic', 'Shure', 'SM58'),
    (3, 'Lighting', 'Aputure 300d', 'Aputure', '300d II'),
    (4, 'Audio', 'Wireless Lav', 'Sennheiser', 'EW-D'),
    (5, 'Lenses', '50mm f1.4', 'Sony', 'FE 50 GM')
  ) as v(k, cat, label, maker, model) where v.k = n % 6
) c;

-- Extra scannable tags: a barcode for every third asset, an NFC UID for every other third.
insert into public.tags (value, kind, asset_id)
select '1100' || lpad(row_number() over (order by a.asset_code)::text, 4, '0'), 'barcode', a.id
from (select * from public.assets order by asset_code) a
where ascii(right(a.asset_code, 1)) % 3 = 0;

insert into public.tags (value, kind, asset_id)
select upper(substr(md5('nfc' || a.id::text), 1, 14)), 'nfc', a.id
from public.assets a
where ascii(right(a.asset_code, 1)) % 3 = 1;

-- History: a created + placement event for every asset.
insert into public.asset_events (id, asset_id, type, payload, occurred_at, device_id)
select gen_random_uuid(), a.id, 'created', jsonb_build_object('name', a.name, 'source', 'seed'),
       now() - interval '60 days', 'seed'
from public.assets a;

-- Overdue checkout: three cameras/lenses out on the Brand Shoot, due a week ago.
insert into public.checkouts (id, project_id, reason, due_at, created_at)
values ('00000000-0000-4000-8000-0000000000c1', '00000000-0000-4000-8000-0000000000a1',
        null, now() - interval '7 days', now() - interval '14 days');

insert into public.checkout_items (checkout_id, asset_id, checked_out_at)
select '00000000-0000-4000-8000-0000000000c1', a.id, now() - interval '14 days'
from (select id from public.assets where not is_container and parent_asset_id is null order by asset_code limit 3) a;

update public.assets set status = 'checked_out'
where id in (select asset_id from public.checkout_items where checkout_id = '00000000-0000-4000-8000-0000000000c1');

insert into public.asset_events (id, asset_id, type, checkout_id, payload, occurred_at, device_id)
select gen_random_uuid(), ci.asset_id, 'checked_out', ci.checkout_id, '{"source":"seed"}'::jsonb,
       now() - interval '14 days', 'seed'
from public.checkout_items ci where ci.checkout_id = '00000000-0000-4000-8000-0000000000c1';
