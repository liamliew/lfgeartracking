begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

insert into auth.users (id, email) values ('11111111-0000-4000-8000-000000000004', 'viewer@t.test');

insert into public.locations (id, name, kind) values ('00000000-0000-4000-8000-0000000d0001', 'Tag Site', 'site');
insert into public.assets (id, name, is_container, parent_location_id) values
  ('00000000-0000-4000-8000-0000000b0001', 'Tag Case', true, '00000000-0000-4000-8000-0000000d0001');
insert into public.assets (id, name, parent_asset_id) values
  ('00000000-0000-4000-8000-0000000b0002', 'Tag Mic', '00000000-0000-4000-8000-0000000b0001');

insert into public.tags (value, kind, asset_id) values
  ('04-46-88-0a-bf-2a-81', 'nfc', '00000000-0000-4000-8000-0000000b0002'),
  ('  110164 ', 'barcode', '00000000-0000-4000-8000-0000000b0002');

-- normalisation
select is((select count(*)::int from public.tags where value = '0446880ABF2A81' and kind = 'nfc'), 1,
  'NFC UIDs are stored as uppercase hex without separators');
select is((select count(*)::int from public.tags where value = '110164' and kind = 'barcode'), 1,
  'barcodes are trimmed');
select throws_ok(
  $$insert into public.tags (value, kind, asset_id) values ('ZZ', 'nfc', '00000000-0000-4000-8000-0000000b0002')$$,
  '22023', 'invalid NFC UID: expected at least 4 bytes of hex', 'invalid NFC UID is rejected');
select throws_ok(
  $$insert into public.tags (value, kind, asset_id) values ('   ', 'barcode', '00000000-0000-4000-8000-0000000b0002')$$,
  '22023', 'tag value must not be empty', 'empty barcode is rejected');

-- a tag resolves to exactly one target
select throws_ok(
  $$insert into public.tags (value, kind, asset_id, location_id)
    values ('BOTH1', 'barcode', '00000000-0000-4000-8000-0000000b0002', '00000000-0000-4000-8000-0000000d0001')$$,
  '23514', 'new row for relation "tags" violates check constraint "tags_exactly_one_target"',
  'a tag cannot point at both an asset and a location');
select throws_ok(
  $$insert into public.tags (value, kind) values ('NONE1', 'barcode')$$,
  '23514', 'new row for relation "tags" violates check constraint "tags_exactly_one_target"',
  'a tag must point at something');
select throws_ok(
  $$insert into public.tags (value, kind, asset_id) values ('110164', 'barcode', '00000000-0000-4000-8000-0000000b0001')$$,
  '23505', 'duplicate key value violates unique constraint "tags_pkey"', 'a tag value is unique');

-- resolve_tag as a viewer
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000004","role":"authenticated"}', true);

select is(public.resolve_tag('110164') ->> 'kind', 'asset', 'barcode resolves to an asset');
select is(public.resolve_tag('110164') ->> 'name', 'Tag Mic', 'resolve returns the name');
select is(public.resolve_tag('0446880ABF2A81') ->> 'id', '00000000-0000-4000-8000-0000000b0002', 'NFC UID resolves');
select is(public.resolve_tag('04-46-88-0A-BF-2A-81') ->> 'id', '00000000-0000-4000-8000-0000000b0002',
  'legacy dash-separated NFC ids still resolve (old labels keep working)');
select is(public.resolve_tag('0446880abf2a81') ->> 'id', '00000000-0000-4000-8000-0000000b0002', 'lowercase hex resolves');
select is(public.resolve_tag('110164') -> 'location' ->> 'path', 'Tag Site',
  'effective location is derived through the container');
select is(public.resolve_tag('110164') -> 'container' ->> 'name', 'Tag Case', 'container is reported');
select is((select public.resolve_tag(a.asset_code) ->> 'kind' from public.assets a where a.id = '00000000-0000-4000-8000-0000000b0001'),
  'container', 'containers resolve as kind container');
select is((select public.resolve_tag(l.code) ->> 'kind' from public.locations l where l.id = '00000000-0000-4000-8000-0000000d0001'),
  'location', 'location codes resolve as kind location');
select is(public.resolve_tag('NOPE-404') ->> 'kind', 'unknown', 'unknown tags report unknown');

reset role;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-0000000000ff","role":"authenticated"}', true);
select throws_ok($$select public.resolve_tag('110164')$$, '28000', 'not authenticated',
  'a signed-in user without a profile cannot resolve tags');

reset role;
set local role anon;
select throws_ok($$select public.resolve_tag('110164')$$, '42501', 'permission denied for function resolve_tag',
  'anon cannot call resolve_tag');

select * from finish();
rollback;
