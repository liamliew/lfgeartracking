begin;
create extension if not exists pgtap with schema extensions;
select plan(14);

insert into auth.users (id, email) values
  ('11111111-0000-4000-8000-000000000003', 'crew@t.test'),
  ('11111111-0000-4000-8000-000000000004', 'viewer@t.test');
update public.profiles set role = 'crew', display_name = 'Casey Crew' where id = '11111111-0000-4000-8000-000000000003';

insert into public.categories (id, name) values ('00000000-0000-4000-8000-0000000b1001', 'Detail Mics');
insert into public.locations (id, name, kind) values ('00000000-0000-4000-8000-0000000b2001', 'Detail Site', 'site');
insert into public.locations (id, name, kind, parent_id) values
  ('00000000-0000-4000-8000-0000000b2002', 'Detail Bin', 'bin', '00000000-0000-4000-8000-0000000b2001');
insert into public.assets (id, name, is_container, parent_location_id) values
  ('00000000-0000-4000-8000-0000000b3001', 'Detail Case', true, '00000000-0000-4000-8000-0000000b2002');
insert into public.assets (id, name, is_container, parent_asset_id) values
  ('00000000-0000-4000-8000-0000000b3002', 'Detail Pouch', true, '00000000-0000-4000-8000-0000000b3001');
insert into public.assets (id, name, category_id, serial_number, parent_asset_id, home_location_id) values
  ('00000000-0000-4000-8000-0000000b3003', 'Detail Mic', '00000000-0000-4000-8000-0000000b1001', 'SN-D1',
   '00000000-0000-4000-8000-0000000b3002', '00000000-0000-4000-8000-0000000b2002');
insert into public.tags (value, kind, asset_id) values ('0446880ABF2A81', 'nfc', '00000000-0000-4000-8000-0000000b3003');
insert into public.asset_events (id, asset_id, type, to_asset_id, occurred_at, user_id) values
  ('00000000-0000-4000-8000-0000000b4001', '00000000-0000-4000-8000-0000000b3003', 'placed_in_container',
   '00000000-0000-4000-8000-0000000b3002', '2026-10-01T10:00:00Z', '11111111-0000-4000-8000-000000000003'),
  ('00000000-0000-4000-8000-0000000b4002', '00000000-0000-4000-8000-0000000b3003', 'seen', null, '2026-10-02T10:00:00Z', null);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000004","role":"authenticated"}', true);

create temp table d as select public.get_asset_detail('00000000-0000-4000-8000-0000000b3003') as j;

select is((select j ->> 'found' from d), 'true', 'a viewer can read asset detail');
select is((select j -> 'asset' ->> 'name' from d), 'Detail Mic', 'asset fields are returned');
select is((select j -> 'asset' -> 'category' ->> 'name' from d), 'Detail Mics', 'category name is returned');
select is((select j -> 'location' ->> 'path' from d), 'Detail Site > Detail Bin', 'location path is derived through the containers');
select is((select jsonb_array_length(j -> 'container_chain') from d), 2, 'container chain has both containers');
select is((select j -> 'container_chain' -> 0 ->> 'name' from d), 'Detail Case', 'container chain is outermost first');
select is((select j -> 'container_chain' -> 1 ->> 'name' from d), 'Detail Pouch', 'and ends with the direct container');
select is((select t ->> 'value' from d, jsonb_array_elements(d.j -> 'tags') t where t ->> 'kind' = 'nfc'), '0446880ABF2A81', 'the NFC tag is listed');
select is((select j -> 'tags' -> 0 ->> 'value' from d), (select asset_code from public.assets where id = '00000000-0000-4000-8000-0000000b3003'),
  'the printed-code tag is always listed first');
select is((select j -> 'events' -> 0 ->> 'type' from d), 'seen', 'timeline is newest first');
select is((select j -> 'events' -> 1 ->> 'user_name' from d), 'Casey Crew', 'timeline shows who did it');
select is((select j -> 'events' -> 1 -> 'to_asset' ->> 'name' from d), 'Detail Pouch', 'timeline resolves the target container');
select is(public.get_asset_detail('00000000-0000-4000-8000-0000000bffff') ->> 'found', 'false', 'an unknown asset reports found = false');
reset role;

set local role anon;
select throws_ok($$select public.get_asset_detail('00000000-0000-4000-8000-0000000b3003')$$, '42501',
  'permission denied for function get_asset_detail', 'anon cannot call it');

select * from finish();
rollback;
