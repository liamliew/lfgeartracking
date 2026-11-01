begin;
create extension if not exists pgtap with schema extensions;
select plan(23);

insert into auth.users (id, email) values
  ('11111111-0000-4000-8000-000000000003', 'crew@t.test'),
  ('11111111-0000-4000-8000-000000000004', 'viewer@t.test');
update public.profiles set role = 'crew' where id = '11111111-0000-4000-8000-000000000003';

insert into public.locations (id, name, kind) values ('00000000-0000-4000-8000-000000001001', 'Create Site', 'site');
insert into public.categories (id, name, default_is_container) values
  ('00000000-0000-4000-8000-000000001101', 'Create Cases', true),
  ('00000000-0000-4000-8000-000000001102', 'Create Mics', false);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000004","role":"authenticated"}', true);
select throws_ok($$select public.create_asset('{"name":"x"}'::jsonb)$$, '42501',
  'insufficient privilege to create assets', 'viewer cannot create assets');
reset role;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000003","role":"authenticated"}', true);

-- happy path: asset + tag + placement, atomically
create temp table c1 as select public.create_asset($j$
 {"id":"40000000-0000-4000-8000-000000000001","name":"SM58 #1","category_id":"00000000-0000-4000-8000-000000001102",
  "manufacturer":"Shure","model":"SM58","serial_number":"SN-12345",
  "tag":{"value":"9900001","kind":"barcode"},"location_id":"00000000-0000-4000-8000-000000001001",
  "occurred_at":"2026-10-01T10:00:00Z","device_id":"dev1"}$j$::jsonb) as j;
select is((select j ->> 'status' from c1), 'created', 'asset is created');
select matches((select j ->> 'asset_code' from c1), '^A[0-9]{6}$', 'an A-code is generated');
select is((select parent_location_id from public.assets where id = '40000000-0000-4000-8000-000000000001'),
  '00000000-0000-4000-8000-000000001001'::uuid, 'first placement is applied');
select is((select count(*)::int from public.tags where asset_id = '40000000-0000-4000-8000-000000000001'), 2,
  'the scanned tag and the printed-code tag both exist');
select is((select array_agg(type::text order by type::text) from public.asset_events where asset_id = '40000000-0000-4000-8000-000000000001'),
  array['created','placed_in_location','tag_assigned'], 'created, tag_assigned and placed_in_location events are logged');
select is(public.resolve_tag('9900001') ->> 'name', 'SM58 #1', 'the new tag resolves immediately');

-- idempotent by client id
create temp table c2 as select public.create_asset($j$
 {"id":"40000000-0000-4000-8000-000000000001","name":"SM58 #1","tag":{"value":"9900001","kind":"barcode"}}$j$::jsonb) as j;
select is((select j ->> 'status' from c2), 'duplicate', 'retrying with the same id is a duplicate');
select is((select count(*)::int from public.assets where name = 'SM58 #1'), 1, 'and creates nothing new');

-- tag already used: show the existing asset instead
create temp table c3 as select public.create_asset($j$
 {"name":"SM58 #2","tag":{"value":"9900001","kind":"barcode"}}$j$::jsonb) as j;
select is((select j ->> 'reason' from c3), 'tag_in_use', 'a used tag is rejected');
select is((select j -> 'existing' ->> 'name' from c3), 'SM58 #1', 'and the existing asset is returned');
select is((select count(*)::int from public.assets where name = 'SM58 #2'), 0, 'nothing is half-created');

-- pre-printed label becomes the code
create temp table c4 as select public.create_asset($j$
 {"name":"Preprinted","tag":{"value":"A000900","kind":"barcode"}}$j$::jsonb) as j;
select is((select j ->> 'asset_code' from c4), 'A000900', 'a pre-printed A-code label becomes the asset code');
select is((select count(*)::int from public.tags where value = 'A000900'), 1, 'with exactly one tag row');

-- NFC normalisation and category default container
create temp table c5 as select public.create_asset($j$
 {"name":"Hard Case","category_id":"00000000-0000-4000-8000-000000001101",
  "tag":{"value":"04-aa-bb-cc-dd-ee-ff","kind":"nfc"}}$j$::jsonb) as j;
select is((select count(*)::int from public.tags where value = '04AABBCCDDEEFF' and kind = 'nfc'), 1, 'NFC tag is normalised');
select is((select (j -> 'asset' ->> 'is_container')::boolean from c5), true, 'category default makes it a container');
select matches((select j ->> 'asset_code' from c5), '^C[0-9]{6}$', 'containers get a C-code');

-- validation
create temp table c6 as select public.create_asset($j$
 {"name":"Nowhere","location_id":"00000000-0000-4000-8000-0000000fffff"}$j$::jsonb) as j;
select is((select j ->> 'reason' from c6), 'location_not_found', 'an unknown location is rejected');
select throws_ok($$select public.create_asset('{"name":"  "}'::jsonb)$$, '22023', 'name is required', 'name is required');

-- search
select ok((select count(*) from public.search_assets('SM58')) >= 1, 'search finds by name');
select is((select count(*)::int from public.search_assets('9900001')), 1, 'search finds by tag');
select is((select count(*)::int from public.search_assets('SN-12345')), 1, 'search finds by serial');
select is((select count(*)::int from public.search_assets('04-AA-BB-CC-DD-EE-FF')), 1, 'search finds legacy dash-separated NFC ids');
reset role;

select * from finish();
rollback;
