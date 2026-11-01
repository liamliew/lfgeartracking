begin;
create extension if not exists pgtap with schema extensions;
select plan(16);

-- containers: B in A, C in B (A is placed in a location)
insert into public.locations (id, name, kind) values ('00000000-0000-4000-8000-0000000c0001', 'Cyc Site', 'site');
insert into public.assets (id, name, is_container, parent_location_id) values
  ('00000000-0000-4000-8000-0000000a0001', 'Cyc A', true, '00000000-0000-4000-8000-0000000c0001');
insert into public.assets (id, name, is_container, parent_asset_id) values
  ('00000000-0000-4000-8000-0000000a0002', 'Cyc B', true, '00000000-0000-4000-8000-0000000a0001');
insert into public.assets (id, name, is_container, parent_asset_id) values
  ('00000000-0000-4000-8000-0000000a0003', 'Cyc C', true, '00000000-0000-4000-8000-0000000a0002');
insert into public.assets (id, name, is_container) values
  ('00000000-0000-4000-8000-0000000a0004', 'Cyc plain item', false);

select throws_ok(
  $$update public.assets set parent_asset_id = '00000000-0000-4000-8000-0000000a0003' where id = '00000000-0000-4000-8000-0000000a0001'$$,
  '23514', 'cycle detected: a container cannot be placed inside itself or its own contents',
  'a container cannot go inside its own grandchild');
select throws_ok(
  $$update public.assets set parent_asset_id = '00000000-0000-4000-8000-0000000a0001' where id = '00000000-0000-4000-8000-0000000a0001'$$,
  '23514', 'cycle detected: a container cannot be placed inside itself or its own contents',
  'a container cannot contain itself');
select throws_ok(
  $$update public.assets set parent_asset_id = '00000000-0000-4000-8000-0000000a0004' where id = '00000000-0000-4000-8000-0000000a0003'$$,
  '23514', 'parent asset is not a container', 'only containers can be parents');
select throws_ok(
  $$update public.assets set is_container = false where id = '00000000-0000-4000-8000-0000000a0001'$$,
  '23514', 'cannot unset is_container while the asset holds items', 'cannot demote a non-empty container');
select throws_ok(
  $$update public.assets set parent_asset_id = '00000000-0000-4000-8000-0000000a0001',
                          parent_location_id = '00000000-0000-4000-8000-0000000c0001'
    where id = '00000000-0000-4000-8000-0000000a0004'$$,
  '23514', 'new row for relation "assets" violates check constraint "assets_single_parent"',
  'an asset has at most one parent');

select is(
  (select location_id from public.asset_effective_location where asset_id = '00000000-0000-4000-8000-0000000a0003'),
  '00000000-0000-4000-8000-0000000c0001'::uuid, 'nested container inherits the outer container location');
select is(
  (select container_depth from public.asset_effective_location where asset_id = '00000000-0000-4000-8000-0000000a0003'),
  2, 'container_depth counts containers walked');

-- depth cap: chain of 10 is fine, the 11th level is refused
do $$
declare prev uuid := null; cur uuid;
begin
  for i in 1..10 loop
    insert into public.assets (name, is_container, parent_asset_id) values ('chain' || i, true, prev) returning id into cur;
    prev := cur;
  end loop;
end $$;
select throws_ok(
  $$insert into public.assets (name, is_container, parent_asset_id)
    values ('chain11', true, (select id from public.assets where name = 'chain10'))$$,
  '23514', 'container nesting too deep (max 10 levels)', 'container nesting is capped at 10');

-- locations
insert into public.locations (id, name, kind, parent_id) values
  ('00000000-0000-4000-8000-0000000c0010', 'Root', 'site', null);
insert into public.locations (id, name, kind, parent_id) values
  ('00000000-0000-4000-8000-0000000c0011', 'Kid', 'room', '00000000-0000-4000-8000-0000000c0010');
insert into public.locations (id, name, kind, parent_id) values
  ('00000000-0000-4000-8000-0000000c0012', 'Grand', 'bin', '00000000-0000-4000-8000-0000000c0011');

select is((select path_cache from public.locations where id = '00000000-0000-4000-8000-0000000c0012'),
  'Root > Kid > Grand', 'path_cache is built from ancestors');
update public.locations set name = 'Base' where id = '00000000-0000-4000-8000-0000000c0010';
select is((select path_cache from public.locations where id = '00000000-0000-4000-8000-0000000c0012'),
  'Base > Kid > Grand', 'renaming an ancestor rewrites descendant paths');
update public.locations set parent_id = null where id = '00000000-0000-4000-8000-0000000c0011';
select is((select path_cache from public.locations where id = '00000000-0000-4000-8000-0000000c0012'),
  'Kid > Grand', 'reparenting rewrites descendant paths');

select throws_ok(
  $$update public.locations set parent_id = '00000000-0000-4000-8000-0000000c0012' where id = '00000000-0000-4000-8000-0000000c0011'$$,
  '23514', 'cycle detected: a location cannot be placed inside itself or its own children',
  'a location cannot move under its own descendant');
select throws_ok(
  $$update public.locations set parent_id = id where id = '00000000-0000-4000-8000-0000000c0011'$$,
  '23514', 'cycle detected: a location cannot be placed inside itself or its own children',
  'a location cannot be its own parent');

do $$
declare prev uuid := null; cur uuid;
begin
  for i in 1..10 loop
    insert into public.locations (name, parent_id) values ('depth' || i, prev) returning id into cur;
    prev := cur;
  end loop;
end $$;
select throws_ok(
  $$insert into public.locations (name, parent_id) values ('depth11', (select id from public.locations where name = 'depth10'))$$,
  '23514', 'location hierarchy too deep (max 10 levels)', 'location hierarchy is capped at 10');

-- every location and asset is scannable by its printed code
select is((select count(*)::int from public.tags t join public.locations l on l.id = t.location_id and l.code = t.value
           where l.id = '00000000-0000-4000-8000-0000000c0010'), 1, 'location code is a barcode tag');
select is((select count(*)::int from public.tags t join public.assets a on a.id = t.asset_id and a.asset_code = t.value
           where a.id = '00000000-0000-4000-8000-0000000a0001'), 1, 'asset code is a barcode tag');

select * from finish();
rollback;
