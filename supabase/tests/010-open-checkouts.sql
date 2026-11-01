begin;
create extension if not exists pgtap with schema extensions;
select plan(6);

insert into auth.users (id, email) values ('11111111-0000-4000-8000-000000000004', 'viewer@t.test');
insert into public.assets (id, name) values
  ('00000000-0000-4000-8000-0000000c3001', 'Out Mic'),
  ('00000000-0000-4000-8000-0000000c3002', 'Returned Mic');
insert into public.checkouts (id, reason, due_at) values
  ('00000000-0000-4000-8000-0000000c4001', 'Open checkout test', '2026-10-10T09:00:00Z');
insert into public.checkout_items (checkout_id, asset_id, checked_out_at, returned_at) values
  ('00000000-0000-4000-8000-0000000c4001', '00000000-0000-4000-8000-0000000c3001', '2026-10-01T09:00:00Z', null),
  ('00000000-0000-4000-8000-0000000c4001', '00000000-0000-4000-8000-0000000c3002', '2026-10-01T09:00:00Z', '2026-10-02T09:00:00Z');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000004","role":"authenticated"}', true);

select is((select count(*)::int from public.get_open_checkouts() where checkout_id = '00000000-0000-4000-8000-0000000c4001'), 1,
  'only open lines are returned');
select is((select asset_id from public.get_open_checkouts() where checkout_id = '00000000-0000-4000-8000-0000000c4001'),
  '00000000-0000-4000-8000-0000000c3001'::uuid, 'and it is the asset that is still out');
select is((select reason from public.get_open_checkouts() where checkout_id = '00000000-0000-4000-8000-0000000c4001'),
  'Open checkout test', 'with the checkout reason');
select is((select due_at from public.get_open_checkouts() where checkout_id = '00000000-0000-4000-8000-0000000c4001'),
  '2026-10-10T09:00:00Z'::timestamptz, 'and the due date');
select ok((select count(*) from public.get_open_checkouts() where checkout_id = '00000000-0000-4000-8000-0000000c4001'
           and asset_id = '00000000-0000-4000-8000-0000000c3002') = 0, 'returned lines are excluded');
reset role;

set local role anon;
select throws_ok($$select * from public.get_open_checkouts()$$, '42501', 'permission denied for function get_open_checkouts', 'anon cannot call it');

select * from finish();
rollback;
