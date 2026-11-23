-- A session ends after 7 days without activity. The apps do this themselves when they are opened; this nightly job
-- covers devices that never come back (a lost phone, an uninstalled app, a cleared browser).
create extension if not exists pg_cron with schema pg_catalog;

-- Run by the job only, as its owner: nobody can call it through the API.
create function public.end_idle_sessions(idle interval default interval '7 days')
returns integer
language plpgsql
set search_path = ''
as $$
declare
  ended integer;
begin
  -- A wrong argument must not sign everyone out.
  if idle < interval '1 day' then
    raise exception 'end_idle_sessions: % is too short', idle;
  end if;
  -- auth.sessions keeps refreshed_at in UTC, without a time zone. Its refresh tokens go with it (on delete cascade).
  delete from auth.sessions s
  where greatest(s.refreshed_at, s.updated_at at time zone 'utc', s.created_at at time zone 'utc')
    < (now() at time zone 'utc') - idle;
  get diagnostics ended = row_count;
  return ended;
end;
$$;

revoke all on function public.end_idle_sessions(interval) from public, anon, authenticated, service_role;

-- Every night at 03:17 UTC. Scheduling under a name replaces a job of that name.
select cron.schedule('end-idle-sessions', '17 3 * * *', $job$select public.end_idle_sessions()$job$);
