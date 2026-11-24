-- pgTAP tests for the nightly job that ends sessions with no activity for 7 days.
begin;
create extension if not exists pgtap with schema extensions;
select * from no_plan();

insert into auth.users (id, email, aud, role) values
  ('11111111-1111-1111-1111-111111111111', 'ana@test.local', 'authenticated', 'authenticated');

insert into auth.sessions (id, user_id, created_at, updated_at, refreshed_at) values
  -- Last refreshed 8 days ago: ended.
  ('aaaaaaaa-0000-4000-8000-000000000001', '11111111-1111-1111-1111-111111111111', now() - interval '30 days', now() - interval '30 days', (now() at time zone 'utc') - interval '8 days'),
  -- Old, but refreshed 6 days ago: stays.
  ('aaaaaaaa-0000-4000-8000-000000000002', '11111111-1111-1111-1111-111111111111', now() - interval '30 days', now() - interval '30 days', (now() at time zone 'utc') - interval '6 days'),
  -- Never refreshed, created 8 days ago: ended.
  ('aaaaaaaa-0000-4000-8000-000000000003', '11111111-1111-1111-1111-111111111111', now() - interval '8 days', now() - interval '8 days', null),
  -- Never refreshed, created yesterday: stays.
  ('aaaaaaaa-0000-4000-8000-000000000004', '11111111-1111-1111-1111-111111111111', now() - interval '1 day', now() - interval '1 day', null);

insert into auth.refresh_tokens (token, user_id, session_id, revoked) values
  ('idle-token', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-0000-4000-8000-000000000001', false),
  ('live-token', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-0000-4000-8000-000000000002', false);

select is(public.end_idle_sessions(), 2, 'the two idle sessions end');
select results_eq(
  $$select id::text from auth.sessions order by id$$,
  array['aaaaaaaa-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000004'],
  'sessions used in the last 7 days stay'
);
select results_eq(
  $$select token::text from auth.refresh_tokens$$,
  array['live-token'],
  'an ended session can no longer be refreshed'
);
select is(public.end_idle_sessions(), 0, 'running it again ends nothing');
select throws_ok($$select public.end_idle_sessions(interval '1 hour')$$, 'P0001', null, 'a too-short limit is refused');

select ok(not has_function_privilege('anon', 'public.end_idle_sessions(interval)', 'execute'), 'anon cannot call it');
select ok(not has_function_privilege('authenticated', 'public.end_idle_sessions(interval)', 'execute'), 'a signed-in user cannot call it');
select ok(not has_function_privilege('service_role', 'public.end_idle_sessions(interval)', 'execute'), 'the service role cannot call it');
select is(
  (select schedule from cron.job where jobname = 'end-idle-sessions'),
  '17 3 * * *',
  'it runs every night'
);
select is(
  (select command from cron.job where jobname = 'end-idle-sessions'),
  'select public.end_idle_sessions()',
  'with the 7-day limit'
);

select * from finish();
rollback;
