begin;
create extension if not exists pgtap with schema extensions;
select plan(35);

insert into auth.users (id, email) values
  ('11111111-0000-4000-8000-000000000002', 'manager@t.test'),
  ('11111111-0000-4000-8000-000000000003', 'crew@t.test'),
  ('11111111-0000-4000-8000-000000000004', 'viewer@t.test');
update public.profiles set role = 'manager' where id = '11111111-0000-4000-8000-000000000002';
update public.profiles set role = 'crew'    where id = '11111111-0000-4000-8000-000000000003';

-- fixtures: two locations, two containers (CA in L2), items I1 I2 I3
insert into public.locations (id, name, kind) values
  ('00000000-0000-4000-8000-0000000f0001', 'Ev L1', 'site'),
  ('00000000-0000-4000-8000-0000000f0002', 'Ev L2', 'site');
insert into public.assets (id, name, is_container, parent_location_id) values
  ('00000000-0000-4000-8000-0000000f0011', 'Ev CA', true, '00000000-0000-4000-8000-0000000f0002'),
  ('00000000-0000-4000-8000-0000000f0012', 'Ev CB', true, null);
insert into public.assets (id, name) values
  ('00000000-0000-4000-8000-0000000f0021', 'Ev I1'),
  ('00000000-0000-4000-8000-0000000f0022', 'Ev I2'),
  ('00000000-0000-4000-8000-0000000f0023', 'Ev I3');

-- viewer is refused -------------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000004","role":"authenticated"}', true);
select throws_ok($$select public.apply_scan_events('[]'::jsonb)$$, '42501',
  'insufficient privilege to record scan events', 'viewer cannot apply events');
reset role;

-- crew --------------------------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-0000-4000-8000-000000000003","role":"authenticated"}', true);

-- placement + idempotency
create temp table r1 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000001","type":"placed_in_location","asset_id":"00000000-0000-4000-8000-0000000f0021",
  "to_location_id":"00000000-0000-4000-8000-0000000f0001","occurred_at":"2026-10-01T10:00:00Z","device_id":"dev1",
  "session_id":"20000000-0000-4000-8000-000000000001","session_mode":"pack"}]$j$::jsonb) as j;
select is((select j -> 0 ->> 'status' from r1), 'accepted', 'placement is accepted');
select is((select parent_location_id from public.assets where id = '00000000-0000-4000-8000-0000000f0021'),
  '00000000-0000-4000-8000-0000000f0001'::uuid, 'item is placed in the location');

create temp table r2 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000001","type":"placed_in_location","asset_id":"00000000-0000-4000-8000-0000000f0021",
  "to_location_id":"00000000-0000-4000-8000-0000000f0001","occurred_at":"2026-10-01T10:00:00Z"}]$j$::jsonb) as j;
select is((select j -> 0 ->> 'status' from r2), 'duplicate', 'replaying an event id is a duplicate');
select is((select count(*)::int from public.asset_events where id = '10000000-0000-4000-8000-000000000001'), 1,
  'a retried event is stored once');
select is((select count(*)::int from public.scan_sessions where id = '20000000-0000-4000-8000-000000000001' and mode = 'pack'), 1,
  'the scan session is recorded');
select is((select user_id from public.asset_events where id = '10000000-0000-4000-8000-000000000001'),
  '11111111-0000-4000-8000-000000000003'::uuid, 'the server stamps the user, not the client');

-- container placement: location becomes derived
create temp table r3 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000002","type":"placed_in_container","asset_id":"00000000-0000-4000-8000-0000000f0021",
  "to_asset_id":"00000000-0000-4000-8000-0000000f0011","occurred_at":"2026-10-01T10:01:00Z"}]$j$::jsonb) as j;
select is((select j -> 0 ->> 'status' from r3), 'accepted', 'placing into a container is accepted');
select is((select parent_location_id from public.assets where id = '00000000-0000-4000-8000-0000000f0021'), null::uuid,
  'an item in a container stores no location of its own');
select is((select location_id from public.asset_effective_location where asset_id = '00000000-0000-4000-8000-0000000f0021'),
  '00000000-0000-4000-8000-0000000f0002'::uuid, 'its effective location is the container location');

-- moving the container moves the contents
create temp table r4 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000003","type":"placed_in_location","asset_id":"00000000-0000-4000-8000-0000000f0011",
  "to_location_id":"00000000-0000-4000-8000-0000000f0001","occurred_at":"2026-10-01T10:02:00Z"}]$j$::jsonb) as j;
select is((select location_id from public.asset_effective_location where asset_id = '00000000-0000-4000-8000-0000000f0021'),
  '00000000-0000-4000-8000-0000000f0001'::uuid, 'moving a container moves its contents');

-- cycles are rejected and leave no trace
create temp table r5 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000004","type":"placed_in_container","asset_id":"00000000-0000-4000-8000-0000000f0011",
  "to_asset_id":"00000000-0000-4000-8000-0000000f0011","occurred_at":"2026-10-01T10:03:00Z"}]$j$::jsonb) as j;
select is((select j -> 0 ->> 'status' from r5), 'rejected', 'a container inside itself is rejected');
select is((select j -> 0 ->> 'reason' from r5),
  'cycle detected: a container cannot be placed inside itself or its own contents', 'the rejection carries a reason');
select is((select count(*)::int from public.asset_events where id = '10000000-0000-4000-8000-000000000004'), 0,
  'rejected events are not stored');

create temp table r6 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000005","type":"placed_in_container","asset_id":"00000000-0000-4000-8000-0000000f0012",
  "to_asset_id":"00000000-0000-4000-8000-0000000f0011","occurred_at":"2026-10-01T10:04:00Z"},
 {"id":"10000000-0000-4000-8000-000000000006","type":"placed_in_container","asset_id":"00000000-0000-4000-8000-0000000f0011",
  "to_asset_id":"00000000-0000-4000-8000-0000000f0012","occurred_at":"2026-10-01T10:04:30Z"}]$j$::jsonb) as j;
select is((select j -> 0 ->> 'status' from r6), 'accepted', 'nesting CB in CA is accepted');
select is((select j -> 1 ->> 'status' from r6), 'rejected', 'then CA in CB is rejected (cycle)');

-- ordering inside a batch follows occurred_at, not array order; one bad event does not poison the batch
create temp table r7 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000007","type":"placed_in_location","asset_id":"00000000-0000-4000-8000-0000000f0022",
  "to_location_id":"00000000-0000-4000-8000-0000000f0002","occurred_at":"2026-10-01T10:05:00Z"},
 {"id":"10000000-0000-4000-8000-000000000008","type":"placed_in_location","asset_id":"00000000-0000-4000-8000-0000000f0022",
  "to_location_id":"00000000-0000-4000-8000-0000000f0001","occurred_at":"2026-10-01T10:00:00Z"},
 {"id":"10000000-0000-4000-8000-000000000009","type":"placed_in_location","asset_id":"00000000-0000-4000-8000-0000000fffff",
  "to_location_id":"00000000-0000-4000-8000-0000000f0001","occurred_at":"2026-10-01T10:06:00Z"}]$j$::jsonb) as j;
select is((select parent_location_id from public.assets where id = '00000000-0000-4000-8000-0000000f0022'),
  '00000000-0000-4000-8000-0000000f0002'::uuid, 'events apply in occurred_at order');
select is((select j -> 2 ->> 'code' from r7), 'P0002', 'an unknown asset is rejected with a code');
select is((select count(*)::int from r7, jsonb_array_elements(r7.j) e where e ->> 'status' = 'accepted'), 2,
  'good events in the same batch still apply');

-- a late-arriving older event is history, not the current state
create temp table r8 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-00000000000a","type":"placed_in_location","asset_id":"00000000-0000-4000-8000-0000000f0022",
  "to_location_id":"00000000-0000-4000-8000-0000000f0001","occurred_at":"2026-10-01T09:00:00Z"}]$j$::jsonb) as j;
select is((select j -> 0 ->> 'reason' from r8), 'superseded', 'an older event is flagged superseded');
select is((select parent_location_id from public.assets where id = '00000000-0000-4000-8000-0000000f0022'),
  '00000000-0000-4000-8000-0000000f0002'::uuid, 'and does not overwrite newer state');
select is((select count(*)::int from public.asset_events where id = '10000000-0000-4000-8000-00000000000a'), 1,
  'but it is still recorded');

-- seen on a container marks contents seen via container
create temp table r9 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-00000000000b","type":"seen","asset_id":"00000000-0000-4000-8000-0000000f0011",
  "lat":3.139,"lng":101.686,"accuracy_m":12,"occurred_at":"2026-10-02T08:00:00Z"}]$j$::jsonb) as j;
select is((select last_seen_at from public.assets where id = '00000000-0000-4000-8000-0000000f0021'),
  '2026-10-02T08:00:00Z'::timestamptz, 'contents are seen via their container');
select is((select parent_asset_id from public.assets where id = '00000000-0000-4000-8000-0000000f0021'),
  '00000000-0000-4000-8000-0000000f0011'::uuid, 'seen does not change placement');

-- check out / transfer / check in
create temp table r10 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000010","type":"checked_out","asset_id":"00000000-0000-4000-8000-0000000f0023",
  "checkout_id":"30000000-0000-4000-8000-000000000001","occurred_at":"2026-10-03T09:00:00Z",
  "payload":{"reason":"Test shoot","due_at":"2026-10-10T09:00:00Z"}}]$j$::jsonb) as j;
select is((select status::text from public.assets where id = '00000000-0000-4000-8000-0000000f0023'), 'checked_out',
  'check out sets the status');
select is((select public.resolve_tag(asset_code) -> 'open_checkout' ->> 'reason' from public.assets where id = '00000000-0000-4000-8000-0000000f0023'),
  'Test shoot', 'resolve_tag reports the open checkout');

create temp table r11 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000011","type":"checked_out","asset_id":"00000000-0000-4000-8000-0000000f0023",
  "checkout_id":"30000000-0000-4000-8000-000000000002","occurred_at":"2026-10-03T10:00:00Z","payload":{"reason":"Other"}}]$j$::jsonb) as j;
select is((select j -> 0 ->> 'code' from r11), '23505', 'an item that is already out is refused unless transfer is set');

create temp table r12 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000012","type":"checked_out","asset_id":"00000000-0000-4000-8000-0000000f0023",
  "checkout_id":"30000000-0000-4000-8000-000000000002","occurred_at":"2026-10-03T10:00:00Z",
  "payload":{"reason":"Other","transfer":true}}]$j$::jsonb) as j;
select is((select j -> 0 ->> 'status' from r12), 'accepted', 'a transfer is accepted');
select isnt((select closed_at from public.checkouts where id = '30000000-0000-4000-8000-000000000001'), null::timestamptz,
  'the emptied original checkout is closed');

create temp table r13 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000013","type":"checked_in","asset_id":"00000000-0000-4000-8000-0000000f0023",
  "to_location_id":"00000000-0000-4000-8000-0000000f0001","occurred_at":"2026-10-04T09:00:00Z"}]$j$::jsonb) as j;
select is((select status::text from public.assets where id = '00000000-0000-4000-8000-0000000f0023'), 'available', 'check in frees the asset');
select is((select parent_location_id from public.assets where id = '00000000-0000-4000-8000-0000000f0023'),
  '00000000-0000-4000-8000-0000000f0001'::uuid, 'and places it in the chosen location');

create temp table r14 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000014","type":"checked_in","asset_id":"00000000-0000-4000-8000-0000000f0023",
  "occurred_at":"2026-10-04T09:05:00Z"}]$j$::jsonb) as j;
select is((select j -> 0 ->> 'reason' from r14), 'not_checked_out', 'checking in something that is not out is accepted but flagged');

-- status rules and privileges
create temp table r15 as select public.apply_scan_events($j$[
 {"id":"10000000-0000-4000-8000-000000000015","type":"status_changed","asset_id":"00000000-0000-4000-8000-0000000f0023",
  "occurred_at":"2026-10-05T09:00:00Z","payload":{"status":"in_repair"}},
 {"id":"10000000-0000-4000-8000-000000000016","type":"status_changed","asset_id":"00000000-0000-4000-8000-0000000f0023",
  "occurred_at":"2026-10-05T09:01:00Z","payload":{"status":"retired"}},
 {"id":"10000000-0000-4000-8000-000000000017","type":"edited","asset_id":"00000000-0000-4000-8000-0000000f0023",
  "occurred_at":"2026-10-05T09:02:00Z","payload":{"patch":{"name":"renamed"}}}]$j$::jsonb) as j;
select is((select j -> 0 ->> 'status' from r15), 'accepted', 'crew can report damage (in_repair)');
select is((select j -> 1 ->> 'code' from r15), '42501', 'crew cannot retire assets');
select is((select j -> 2 ->> 'code' from r15), '42501', 'crew cannot edit assets');
reset role;

select * from finish();
rollback;
