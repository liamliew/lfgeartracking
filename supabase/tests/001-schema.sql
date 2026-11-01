begin;
create extension if not exists pgtap with schema extensions;
select plan(11);

select has_table('public', 'assets', 'assets exists');
select has_table('public', 'asset_events', 'asset_events exists');
select has_view('public', 'asset_effective_location', 'asset_effective_location view exists');
select has_view('public', 'overdue_checkout_items', 'overdue_checkout_items view exists');

select is(
  (select count(*)::int from pg_class c join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relkind = 'r' and not c.relrowsecurity),
  0, 'RLS is enabled on every table in public');

select is(
  (select count(*)::int from information_schema.role_table_grants
   where table_schema = 'public' and grantee = 'anon'),
  0, 'anon has no table privileges');

select is(
  (select count(*)::int from information_schema.role_table_grants
   where table_schema = 'public' and table_name = 'asset_events'
     and grantee = 'authenticated' and privilege_type in ('INSERT','UPDATE','DELETE','TRUNCATE')),
  0, 'authenticated cannot write asset_events directly');

select is_definer('public', 'apply_scan_events', array['jsonb']);
select is_definer('public', 'create_asset', array['jsonb']);
select is_definer('public', 'resolve_tag', array['text']);

-- append-only trigger blocks even privileged roles
insert into public.assets (id, name) values ('00000000-0000-4000-8000-0000000ffff1', 'Append only probe');
insert into public.asset_events (id, asset_id, type, occurred_at)
values ('00000000-0000-4000-8000-0000000ffff2', '00000000-0000-4000-8000-0000000ffff1', 'seen', now());
select throws_ok(
  $$update public.asset_events set type = 'edited' where id = '00000000-0000-4000-8000-0000000ffff2'$$,
  '42501', 'asset_events is append-only', 'events cannot be updated');

select * from finish();
rollback;
