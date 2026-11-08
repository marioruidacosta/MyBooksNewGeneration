-- pgTAP tests for cloud library sync: sync_push merges per field by time, sync_pull returns my rows after a cursor.
begin;
create extension if not exists pgtap with schema extensions;
select * from no_plan();

insert into auth.users (id, email, aud, role) values
  ('11111111-1111-1111-1111-111111111111', 'ana@test.local', 'authenticated', 'authenticated'),
  ('22222222-2222-2222-2222-222222222222', 'bruno@test.local', 'authenticated', 'authenticated');

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', uid::text, 'role', 'authenticated')::text, true);
$$;
create function pg_temp.now_ms() returns bigint language sql as $$
  select (extract(epoch from clock_timestamp()) * 1000)::bigint;
$$;
create function pg_temp.push_book(p_id text, p_fields jsonb, p_times jsonb) returns jsonb language sql as $$
  select sync_push(jsonb_build_array(jsonb_build_object('table', 'books', 'id', p_id, 'fields', p_fields, 'times', p_times)));
$$;
create function pg_temp.title_of(p_id text) returns text language sql as $$
  select title from library_books where owner = auth.uid() and id = p_id;
$$;

create function pg_temp.bulk(a int, b int) returns jsonb language sql as $$
  select sync_push((select jsonb_agg(jsonb_build_object('table','prefs','id','bulk' || g,'fields','{"value":"v"}'::jsonb,
    'times','{"value":1000}'::jsonb)) from generate_series(a, b) g));
$$;
set local role authenticated;
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');

-- push_inserts_then_pull_returns_it
select pg_temp.push_book('b1', '{"title":"Os Maias"}', '{"title":1000}');
select is(
  (select count(*)::int from jsonb_array_elements(sync_pull(0)->'rows') r where r->>'id' = 'b1'),
  1, 'push_inserts_then_pull_returns_it: one row');
select is(
  (select r->'fields'->>'title' from jsonb_array_elements(sync_pull(0)->'rows') r where r->>'id' = 'b1'),
  'Os Maias', 'push_inserts_then_pull_returns_it: title');
select is(
  (select (r->'times'->>'title')::bigint from jsonb_array_elements(sync_pull(0)->'rows') r where r->>'id' = 'b1'),
  1000::bigint, 'push_inserts_then_pull_returns_it: times.title');

-- newer_field_wins_older_loses
select pg_temp.push_book('b2', '{"title":"A"}', '{"title":2000}');
select pg_temp.push_book('b2', '{"title":"B"}', '{"title":1500}');
select is(pg_temp.title_of('b2'), 'A', 'newer_field_wins_older_loses: older title loses');
select pg_temp.push_book('b2', '{"rating":4}', '{"rating":1500}');
select is((select rating from library_books where id = 'b2'), 4, 'newer_field_wins_older_loses: rating without time accepted');

-- equal_time_keeps_stored
select pg_temp.push_book('b3', '{"title":"A"}', '{"title":3000}');
select pg_temp.push_book('b3', '{"title":"Z"}', '{"title":3000}');
select is(pg_temp.title_of('b3'), 'A', 'equal_time_keeps_stored');

-- future_time_capped
select pg_temp.push_book('b4', '{"title":"Future"}', jsonb_build_object('title', pg_temp.now_ms() + 86400000));
select ok(
  (select (field_times->>'title')::bigint <= pg_temp.now_ms() + 60000 from library_books where id = 'b4'),
  'future_time_capped: stored time within now + 1 minute');
select pg_sleep(0.01);
select pg_temp.push_book('b4', '{"title":"Later"}', jsonb_build_object('title', pg_temp.now_ms() + 120000));
select is(pg_temp.title_of('b4'), 'Later', 'future_time_capped: later edit wins');

-- delete_beats_older_edit_and_newer_edit_revives
select pg_temp.push_book('b5', '{"title":"X"}', '{"title":1000}');
select pg_temp.push_book('b5', '{"deleted":true}', '{"deleted":5000}');
select is((select deleted from library_books where id = 'b5'), true, 'delete: deleted is true');
select pg_temp.push_book('b5', '{"title":"Y"}', '{"title":4000}');
select is((select deleted from library_books where id = 'b5'), true, 'delete: older edit does not revive');
select pg_temp.push_book('b5', '{"deleted":false,"title":"C"}', '{"deleted":6000,"title":6000}');
select is((select deleted from library_books where id = 'b5'), false, 'delete: newer edit revives');
select is(pg_temp.title_of('b5'), 'C', 'delete: revived title is C');

-- push_is_idempotent
select pg_temp.push_book('b6', '{"title":"Same"}', '{"title":7000}');
select pg_temp.push_book('b6', '{"title":"Same"}', '{"title":7000}');
select is((select count(*)::int from library_books where id = 'b6'), 1, 'push_is_idempotent: one row');
select is((select field_times from library_books where id = 'b6'), '{"title":7000}'::jsonb,
  'push_is_idempotent: field_times unchanged');

-- bad_row_set_aside_rest_applied
select is(
  (select jsonb_array_length(s.r->'set_aside') from (select sync_push(jsonb_build_array(
    jsonb_build_object('table','books','id','bad','fields',jsonb_build_object('title', repeat('x', 301)),'times','{"title":1000}'::jsonb),
    jsonb_build_object('table','books','id','good','fields','{"title":"Fine"}'::jsonb,'times','{"title":1000}'::jsonb)
  )) as r) s),
  1, 'bad_row_set_aside_rest_applied: one set aside');
select is(pg_temp.title_of('good'), 'Fine', 'bad_row_set_aside_rest_applied: valid change applied');
select is((select count(*)::int from library_books where id = 'bad'), 0, 'bad_row_set_aside_rest_applied: bad row absent');
select ok(
  (select s.r->'set_aside'->0->>'reason' is not null and s.r->'set_aside'->0->>'id' = 'bad'
   from (select sync_push(jsonb_build_array(
    jsonb_build_object('table','books','id','bad','fields',jsonb_build_object('title', repeat('x', 301)),'times','{"title":1000}'::jsonb))) as r) s),
  'bad_row_set_aside_rest_applied: reason given');

-- the other tables work
select sync_push(jsonb_build_array(
  jsonb_build_object('table','challenges','id','2026','fields','{"goal":24}'::jsonb,'times','{"goal":1000}'::jsonb),
  jsonb_build_object('table','prefs','id','pinned','fields','{"value":"a,b"}'::jsonb,'times','{"value":1000}'::jsonb),
  jsonb_build_object('table','quotes','id','q1','fields','{"book_id":"b1","text":"Hello","page":3}'::jsonb,'times','{"text":1000}'::jsonb)
));
select is((select goal from library_challenges where id = '2026'), 24, 'challenge_pushed');
select is((select value from library_prefs where id = 'pinned'), 'a,b', 'pref_pushed');
select is((select text from library_quotes where id = 'q1'), 'Hello', 'quote_pushed');

-- pull_only_mine_after_cursor
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select pg_temp.push_book('bruno1', '{"title":"Bruno book"}', '{"title":1000}');
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select is(
  (select count(*)::int from jsonb_array_elements(sync_pull(0)->'rows') r where r->>'id' = 'bruno1'),
  0, 'pull_only_mine: bruno row never appears');
select is(
  jsonb_array_length(sync_pull((sync_pull(0)->>'last')::bigint)->'rows'),
  0, 'pull_only_mine: nothing new after the cursor');
select is(jsonb_array_length(sync_pull(0, 3)->'rows'), 3, 'pull_only_mine: max limits the page');
select is(
  (select array_agg((r->>'seq')::bigint) from jsonb_array_elements(sync_pull(0)->'rows') r),
  (select array_agg(x.s order by x.s) from (select (r->>'seq')::bigint as s from jsonb_array_elements(sync_pull(0)->'rows') r) x),
  'pull_ordered: rows come in seq order');
select is(
  (select (r->>'deleted')::boolean from jsonb_array_elements(sync_pull(0)->'rows') r where r->>'id' = 'b6'),
  false, 'pull_includes_deleted_flag');

-- sync_pull clamps max to 1..1000
select is(jsonb_array_length(sync_pull(0, 0)->'rows'), 1, 'pull_max_clamped_min: 0 gives one row');
select is(jsonb_array_length(sync_pull(0, -5)->'rows'), 1, 'pull_max_clamped_min: negative gives one row');
select pg_temp.bulk(1, 200); select pg_temp.bulk(201, 400); select pg_temp.bulk(401, 600);
select pg_temp.bulk(601, 800); select pg_temp.bulk(801, 1000); select pg_temp.bulk(1001, 1100);
select is(jsonb_array_length(sync_pull(0, 5000)->'rows'), 1000, 'pull_max_clamped_max: 5000 gives 1000');
select is(jsonb_array_length(sync_pull(0)->'rows'), 500, 'pull_default_max_500');

-- push_over_200_rejected
select throws_ok(
  $$select sync_push((select jsonb_agg(jsonb_build_object('table','prefs','id','over' || g,'fields','{}'::jsonb,'times','{}'::jsonb))
                      from generate_series(1, 201) g))$$,
  '22023', null, 'push_over_200_rejected');
select is(jsonb_array_length(pg_temp.bulk(1, 200)->'applied'), 200, 'push_200_accepted');

-- oversize description is set aside; results carry table and id
select is(
  (select (s.r->'set_aside'->0) - 'reason' from (select pg_temp.push_book('fat', jsonb_build_object('description', repeat('d', 20001)),
    '{"description":1000}') as r) s),
  '{"table":"books","id":"fat"}'::jsonb, 'oversize_description_set_aside: identified by table and id');
select is((select count(*)::int from library_books where id = 'fat'), 0, 'oversize_description_set_aside: not stored');
select is(pg_temp.push_book('ok1', '{"title":"T"}', '{"title":1}')->'applied', '[{"table":"books","id":"ok1"}]'::jsonb,
  'applied_identified_by_table_and_id');

-- rls_hides_other_owner
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select is(
  (select count(*)::int from library_books where owner = '11111111-1111-1111-1111-111111111111'),
  0, 'rls_hides_other_owner');

-- anon_cannot_call
set local role anon;
select throws_ok($$select sync_push('[]'::jsonb)$$, '42501', null, 'anon_cannot_call: sync_push');
select throws_ok($$select sync_pull(0)$$, '42501', null, 'anon_cannot_call: sync_pull');

-- catalogue checks (as the test owner again)
reset role;
select is(
  (select proconfig from pg_proc where proname = 'sync_push'),
  array['search_path=public, pg_temp'], 'search_path_pins_pg_temp_last');
select is(
  has_function_privilege('authenticated', '_merge_row(text,uuid,text,jsonb,jsonb)', 'execute'),
  false, 'merge_row_not_callable');

select * from finish();
rollback;
