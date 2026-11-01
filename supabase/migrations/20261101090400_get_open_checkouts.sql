-- get_open_checkouts: flat list of everything currently checked out, for the device's offline cache.
-- SECURITY INVOKER: RLS applies (every signed-in role can read checkouts). Page it with a range header;
-- PostgREST caps a response at max_rows (1000).

create function public.get_open_checkouts()
returns table (asset_id uuid, checkout_id uuid, project_id uuid, reason text, due_at timestamptz, checked_out_at timestamptz)
language sql stable set search_path = '' as $$
  select ci.asset_id, ci.checkout_id, c.project_id, c.reason, c.due_at, ci.checked_out_at
  from public.checkout_items ci
  join public.checkouts c on c.id = ci.checkout_id
  where ci.returned_at is null
  order by ci.asset_id
$$;

revoke execute on function public.get_open_checkouts() from public, anon;
grant execute on function public.get_open_checkouts() to authenticated, service_role;
