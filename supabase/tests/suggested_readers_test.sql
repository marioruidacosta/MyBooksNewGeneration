-- pgTAP tests for BookNerd's suggested readers: similar taste and friends of friends.
begin;
create extension if not exists pgtap with schema extensions;
select * from no_plan();

-- ana asks. bruno is her friend; dora is bruno's friend. carla and joao are public readers who loved the same
-- books; fred too, but ana already asked him; gil loved them but is friends-only; eve (ana blocked her) and helena
-- (blocked ana) loved them too.
insert into auth.users (id, email, aud, role) values
  ('11111111-1111-1111-1111-111111111111', 'ana@test.local', 'authenticated', 'authenticated'),
  ('22222222-2222-2222-2222-222222222222', 'bruno@test.local', 'authenticated', 'authenticated'),
  ('33333333-3333-3333-3333-333333333333', 'carla@test.local', 'authenticated', 'authenticated'),
  ('44444444-4444-4444-4444-444444444444', 'dora@test.local', 'authenticated', 'authenticated'),
  ('55555555-5555-5555-5555-555555555555', 'eve@test.local', 'authenticated', 'authenticated'),
  ('66666666-6666-6666-6666-666666666666', 'fred@test.local', 'authenticated', 'authenticated'),
  ('77777777-7777-7777-7777-777777777777', 'gil@test.local', 'authenticated', 'authenticated'),
  ('88888888-8888-8888-8888-888888888888', 'helena@test.local', 'authenticated', 'authenticated'),
  ('99999999-9999-9999-9999-999999999999', 'joao@test.local', 'authenticated', 'authenticated');
insert into profiles (id, username, display_name, visibility) values
  ('11111111-1111-1111-1111-111111111111', 'ana', 'Ana', 'friends'),
  ('22222222-2222-2222-2222-222222222222', 'bruno', 'Bruno', 'friends'),
  ('33333333-3333-3333-3333-333333333333', 'carla', 'Carla', 'public'),
  ('44444444-4444-4444-4444-444444444444', 'dora', 'Dora', 'friends'),
  ('55555555-5555-5555-5555-555555555555', 'eve', 'Eve', 'public'),
  ('66666666-6666-6666-6666-666666666666', 'fred', 'Fred', 'public'),
  ('77777777-7777-7777-7777-777777777777', 'gil', 'Gil', 'friends'),
  ('88888888-8888-8888-8888-888888888888', 'helena', 'Helena', 'public'),
  ('99999999-9999-9999-9999-999999999999', 'joao', 'João', 'public');
insert into friendships (requester, addressee, status) values
  ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'accepted'),
  ('44444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222', 'accepted'),
  ('11111111-1111-1111-1111-111111111111', '66666666-6666-6666-6666-666666666666', 'pending');
insert into blocks (blocker, blocked) values
  ('11111111-1111-1111-1111-111111111111', '55555555-5555-5555-5555-555555555555'),
  ('88888888-8888-8888-8888-888888888888', '11111111-1111-1111-1111-111111111111');

-- ana loved A Relíquia (5★) and Os Maias (4★).
insert into shared_books (id, owner, isbn, title, status, rating, updated_at) values
  ('a0000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '9789722036504', 'A Relíquia', 'read', 5, now()),
  ('a0000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '9789722039994', 'Os Maias', 'read', 4, now()),
  ('c0000000-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', '9789722036504', 'A Relíquia', 'read', 5, now() - interval '2 days'),
  ('c0000000-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333', '9789722039994', 'Os Maias', 'read', 4, now() - interval '1 day'),
  ('90000000-0000-0000-0000-000000000001', '99999999-9999-9999-9999-999999999999', '9789722036504', 'A Relíquia', 'read', 5, now()),
  ('60000000-0000-0000-0000-000000000001', '66666666-6666-6666-6666-666666666666', '9789722036504', 'A Relíquia', 'read', 5, now()),
  ('70000000-0000-0000-0000-000000000001', '77777777-7777-7777-7777-777777777777', '9789722036504', 'A Relíquia', 'read', 5, now()),
  ('50000000-0000-0000-0000-000000000001', '55555555-5555-5555-5555-555555555555', '9789722036504', 'A Relíquia', 'read', 5, now()),
  ('80000000-0000-0000-0000-000000000001', '88888888-8888-8888-8888-888888888888', '9789722036504', 'A Relíquia', 'read', 5, now()),
  -- bruno rated it 3★: not a taste match (and he's a friend anyway).
  ('20000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', '9789722036504', 'A Relíquia', 'read', 3, now());

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', uid::text, 'role', 'authenticated')::text, true);
$$;

set local role authenticated;
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');

select results_eq(
  $$select shared_books, top_title from suggested_readers() where username = 'carla'$$,
  $$values (2, 'Os Maias'::text)$$,
  'taste_match_with_count_and_title');
select results_eq(
  $$select mutual_friends, mutual_names from suggested_readers() where username = 'dora'$$,
  $$values (1, array['Bruno']::text[])$$,
  'friend_of_friend_with_names');
select is_empty(
  $$select 1 from suggested_readers() where username in ('ana', 'bruno', 'fred', 'eve', 'helena')$$,
  'excludes_self_friends_pending_blocked');
select is_empty($$select 1 from suggested_readers() where username = 'gil'$$, 'friends_only_reader_not_matched_by_taste');
select results_eq(
  $$select username from suggested_readers()$$,
  $$values ('carla'::text), ('dora'::text), ('joao'::text)$$,
  'ordered_by_score_then_name');

-- At most 20.
reset role;
insert into auth.users (id, email, aud, role)
  select ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, 'u' || n || '@test.local', 'authenticated', 'authenticated'
  from generate_series(1, 25) n;
insert into profiles (id, username, display_name, visibility)
  select ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, 'reader' || n, 'Reader ' || n, 'public'
  from generate_series(1, 25) n;
insert into shared_books (id, owner, isbn, title, status, rating, updated_at)
  select gen_random_uuid(), ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid, '9789722036504', 'A Relíquia', 'read', 5, now()
  from generate_series(1, 25) n;
set local role authenticated;
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select is((select count(*)::int from suggested_readers(50)), 20, 'max_capped_at_20');
select is((select count(*)::int from suggested_readers()), 10, 'ten_by_default');

-- Not callable without signing in.
set local role anon;
select throws_ok($$select * from suggested_readers()$$, '42501', null, 'anon_cannot_call');

-- Catalogue checks (as the test owner again).
reset role;
select has_index('public', 'shared_books', 'shared_books_loved_isbn_idx', 'loved_isbn_index_exists');
select is(
  (select proconfig from pg_proc where proname = 'suggested_readers'),
  array['search_path=public, pg_temp'],
  'search_path_pins_pg_temp_last'
);

select * from finish();
rollback;
