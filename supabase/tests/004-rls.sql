begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

insert into auth.users (id, email) values
  ('11111111-0000-4000-8000-000000000001', 'admin@t.test'),
  ('11111111-0000-4000-8000-000000000002', 'manager@t.test'),
  ('11111111-0000-4000-8000-000000000003', 'crew@t.test'),
  ('11111111-0000-4000-8000-000000000004', 'viewer@t.test');
update public.profiles set role = 'admin'   where id = '11111111-0000-4000-8000-000000000001';
update public.profiles set role = 'manager' where id = '11111111-0000-4000-8000-000000000002';
update public.profiles set role = 'crew'    where id = '11111111-0000-4000-8000-000000000003';

insert into public.assets (id, name) values ('00000000-0000-4000-8000-0000000e0001', 'RLS probe asset');

-- anon -------------------------------------------------------------------------------
set local role anon;
select throws_ok($$select count(*) from public.assets$$, '42501', 'permission denied for table assets',
  'anon cannot read assets');
reset role;

-- viewer -----------------------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000004","role":"authenticated"}', true);
select ok((select count(*) from public.assets) > 0, 'viewer can read assets');
select throws_ok($$insert into public.assets (name) values ('viewer made this')$$, '42501',
  'new row violates row-level security policy for table "assets"', 'viewer cannot insert assets');
update public.assets set name = 'hacked' where id = '00000000-0000-4000-8000-0000000e0001';
select is((select name from public.assets where id = '00000000-0000-4000-8000-0000000e0001'), 'RLS probe asset', 'viewer cannot update assets');
select throws_ok($$select public.apply_scan_events('[]'::jsonb)$$, '42501', 'insufficient privilege to record scan events',
  'viewer cannot record scan events');
reset role;

-- crew -------------------------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000003","role":"authenticated"}', true);
select ok((select count(*) from public.assets) > 0, 'crew can read assets');
select throws_ok($$insert into public.assets (name) values ('crew direct insert')$$, '42501',
  'new row violates row-level security policy for table "assets"', 'crew cannot insert assets directly');
update public.assets set name = 'hacked' where id = '00000000-0000-4000-8000-0000000e0001';
select is((select name from public.assets where id = '00000000-0000-4000-8000-0000000e0001'), 'RLS probe asset', 'crew cannot update assets directly');
select throws_ok($$insert into public.tags (value, kind, asset_id) values ('CREWTAG1', 'barcode', '00000000-0000-4000-8000-0000000e0001')$$,
  '42501', 'new row violates row-level security policy for table "tags"', 'crew cannot insert tags directly');
select throws_ok($$insert into public.asset_events (id, asset_id, type, occurred_at)
    values (gen_random_uuid(), '00000000-0000-4000-8000-0000000e0001', 'seen', now())$$,
  '42501', 'permission denied for table asset_events', 'crew cannot write events directly');
select lives_ok($$insert into public.asset_photos (asset_id, storage_path, created_by)
    values ('00000000-0000-4000-8000-0000000e0001', 'p/1.jpg', '11111111-0000-4000-8000-000000000003')$$,
  'crew can add their own photo');
select throws_ok($$insert into public.asset_photos (asset_id, storage_path, created_by)
    values ('00000000-0000-4000-8000-0000000e0001', 'p/2.jpg', '11111111-0000-4000-8000-000000000002')$$,
  '42501', 'new row violates row-level security policy for table "asset_photos"', 'crew cannot add photos as someone else');
reset role;

-- manager ----------------------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000002","role":"authenticated"}', true);
select lives_ok($$insert into public.assets (name) values ('manager made this')$$, 'manager can insert assets');
update public.assets set notes = 'ok' where id = '00000000-0000-4000-8000-0000000e0001';
select is((select notes from public.assets where id = '00000000-0000-4000-8000-0000000e0001'), 'ok', 'manager can update assets');
select throws_ok($$delete from public.assets where id = '00000000-0000-4000-8000-0000000e0001'$$, '42501',
  'permission denied for table assets', 'nobody deletes assets (soft delete only)');
select throws_ok($$insert into public.categories (name) values ('Manager category')$$, '42501',
  'new row violates row-level security policy for table "categories"', 'manager cannot manage categories');
select throws_ok($$update public.profiles set role = 'admin' where id = '11111111-0000-4000-8000-000000000002'$$, '42501',
  'new row violates row-level security policy for table "profiles"', 'users cannot promote themselves');
reset role;

-- admin ------------------------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000001","role":"authenticated"}', true);
select lives_ok($$insert into public.categories (name) values ('Admin category')$$, 'admin can manage categories');
update public.profiles set role = 'crew' where id = '11111111-0000-4000-8000-000000000004';
select is((select role::text from public.profiles where id = '11111111-0000-4000-8000-000000000004'), 'crew', 'admin can change user roles');
reset role;

select * from finish();
rollback;
