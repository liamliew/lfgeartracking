begin;
create extension if not exists pgtap with schema extensions;
select plan(8);

insert into auth.users (id, email) values ('11111111-0000-4000-8000-000000000004', 'viewer@t.test');
insert into public.locations (id, name, kind) values
  ('00000000-0000-4000-8000-0000000a1001', 'Anc Site', 'site');
insert into public.locations (id, name, kind, parent_id) values
  ('00000000-0000-4000-8000-0000000a1002', 'Anc Room', 'room', '00000000-0000-4000-8000-0000000a1001'),
  ('00000000-0000-4000-8000-0000000a1003', 'Anc Bin', 'bin', '00000000-0000-4000-8000-0000000a1002');
insert into public.assets (id, name, is_container, parent_location_id) values
  ('00000000-0000-4000-8000-0000000a2001', 'Anc Case', true, '00000000-0000-4000-8000-0000000a1003');
insert into public.assets (id, name, is_container, parent_asset_id) values
  ('00000000-0000-4000-8000-0000000a2002', 'Anc Pouch', true, '00000000-0000-4000-8000-0000000a2001');
insert into public.assets (id, name, parent_asset_id) values
  ('00000000-0000-4000-8000-0000000a2003', 'Anc Cable', '00000000-0000-4000-8000-0000000a2002');
insert into public.tags (value, kind, asset_id) values ('ANC-CABLE', 'barcode', '00000000-0000-4000-8000-0000000a2003');
insert into public.tags (value, kind, location_id) values ('ANC-BIN', 'barcode', '00000000-0000-4000-8000-0000000a1003');
insert into public.tags (value, kind, asset_id) values ('ANC-CASE', 'barcode', '00000000-0000-4000-8000-0000000a2001');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000004","role":"authenticated"}', true);

select is(public.resolve_tag('ANC-CABLE') -> 'ancestor_ids',
  '["00000000-0000-4000-8000-0000000a2002","00000000-0000-4000-8000-0000000a2001"]'::jsonb,
  'an item lists its containers, nearest first');
select is(public.resolve_tag('ANC-CABLE') -> 'placement' ->> 'container_id', '00000000-0000-4000-8000-0000000a2002',
  'placement reports the direct container');
select is(public.resolve_tag('ANC-CABLE') -> 'placement' ->> 'location_id', null::text, 'an item in a container has no direct location');
select is(public.resolve_tag('ANC-CASE') -> 'ancestor_ids', '[]'::jsonb, 'a top-level container has no ancestors');
select is(public.resolve_tag('ANC-CASE') -> 'placement' ->> 'location_id', '00000000-0000-4000-8000-0000000a1003',
  'a container in a location reports that location');
select is(public.resolve_tag('ANC-BIN') -> 'ancestor_ids',
  '["00000000-0000-4000-8000-0000000a1002","00000000-0000-4000-8000-0000000a1001"]'::jsonb,
  'a location lists its ancestor locations, nearest first');
select is(public.resolve_tag('ANC-BIN') ->> 'kind', 'location', 'location kind is unchanged');
select is(public.resolve_tag('NOPE') ->> 'kind', 'unknown', 'unknown is unchanged');
reset role;

select * from finish();
rollback;
