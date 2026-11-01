begin;
create extension if not exists pgtap with schema extensions;
select plan(6);

insert into auth.users (id, email) values
  ('11111111-0000-4000-8000-000000000001', 'admin@t.test'),
  ('11111111-0000-4000-8000-000000000003', 'crew@t.test');
update public.profiles set role = 'admin' where id = '11111111-0000-4000-8000-000000000001';
update public.profiles set role = 'crew'  where id = '11111111-0000-4000-8000-000000000003';
insert into public.legacy_id_map (legacy_table, legacy_id, new_table, new_id)
values ('inventory_assets', 'x1', 'assets', '00000000-0000-4000-8000-0000000d1001');

select is((select relrowsecurity from pg_class where oid = 'public.legacy_id_map'::regclass), true, 'RLS is enabled');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000001","role":"authenticated"}', true);
select is((select count(*)::int from public.legacy_id_map), 1, 'admin can read the map');
select throws_ok($$insert into public.legacy_id_map values ('a','b','c','00000000-0000-4000-8000-0000000d1002')$$, '42501',
  'permission denied for table legacy_id_map', 'nobody writes it through the API');
reset role;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000003","role":"authenticated"}', true);
select is((select count(*)::int from public.legacy_id_map), 0, 'crew sees nothing');
reset role;

set local role anon;
select throws_ok($$select * from public.legacy_id_map$$, '42501', 'permission denied for table legacy_id_map', 'anon cannot read it');
reset role;

select throws_ok($$insert into public.legacy_id_map values ('inventory_assets','x1','assets','00000000-0000-4000-8000-0000000d1003')$$,
  '23505', 'duplicate key value violates unique constraint "legacy_id_map_pkey"', 'a legacy row maps once');

select * from finish();
rollback;
