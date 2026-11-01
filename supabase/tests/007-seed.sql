begin;
create extension if not exists pgtap with schema extensions;
select plan(9);

select is((select count(*)::int from public.locations where kind = 'site' and parent_id is null and name in ('Savanna','YF House','Studio HQ')), 3, 'seed: 3 sites');
select ok(exists (select 1 from public.locations where path_cache = 'Savanna > Storage 1 > Shelf A > Cabinet 1 > Bin 1A'), 'seed: room > shelf > cabinet > bin hierarchy');
select is((select count(*)::int from public.categories where name in ('Cameras','Lenses','Audio','Lighting','Cases & Bags')), 5, 'seed: 5 categories');
select ok((select count(*) from public.assets) >= 60, 'seed: about 60 assets');
select is((select count(*)::int from public.assets where is_container and asset_code like 'C%'), 6, 'seed: 6 containers with C-codes');
select is((select count(*)::int from public.assets where is_container and parent_asset_id is not null), 1, 'seed: one nested container');
select ok((select count(*) from public.tags where kind = 'nfc') > 0 and (select count(*) from public.tags where kind = 'barcode') > 0, 'seed: barcode and NFC tags');
select is((select count(*)::int from public.projects), 2, 'seed: 2 projects');
select is((select count(distinct checkout_id)::int from public.overdue_checkout_items), 1, 'seed: 1 overdue checkout');

select * from finish();
rollback;
