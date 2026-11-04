-- pgTAP tests for the book page's community scores and reviews.
begin;
create extension if not exists pgtap with schema extensions;
select * from no_plan();

-- ana and bruno are friends (friends-only); carla and eve are public; dora is friends-only.
-- eve blocked ana. Everyone shares the same book (same ISBN); fred shares another one.
insert into auth.users (id, email, aud, role) values
  ('11111111-1111-1111-1111-111111111111', 'ana@test.local', 'authenticated', 'authenticated'),
  ('22222222-2222-2222-2222-222222222222', 'bruno@test.local', 'authenticated', 'authenticated'),
  ('33333333-3333-3333-3333-333333333333', 'carla@test.local', 'authenticated', 'authenticated'),
  ('44444444-4444-4444-4444-444444444444', 'dora@test.local', 'authenticated', 'authenticated'),
  ('55555555-5555-5555-5555-555555555555', 'eve@test.local', 'authenticated', 'authenticated'),
  ('66666666-6666-6666-6666-666666666666', 'fred@test.local', 'authenticated', 'authenticated');
insert into profiles (id, username, display_name, visibility) values
  ('11111111-1111-1111-1111-111111111111', 'ana', 'Ana', 'friends'),
  ('22222222-2222-2222-2222-222222222222', 'bruno', 'Bruno', 'friends'),
  ('33333333-3333-3333-3333-333333333333', 'carla', 'Carla', 'public'),
  ('44444444-4444-4444-4444-444444444444', 'dora', 'Dora', 'friends'),
  ('55555555-5555-5555-5555-555555555555', 'eve', 'Eve', 'public'),
  ('66666666-6666-6666-6666-666666666666', 'fred', 'Fred', 'public');
insert into friendships (requester, addressee, status) values
  ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'accepted');
insert into blocks (blocker, blocked) values
  ('55555555-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111');

insert into shared_books (id, owner, isbn, title, status, rating, review, updated_at) values
  ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '9789726084884', 'Book', 'read', 3, 'mine', now()),
  ('bbbbbbbb-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', '9789726084884', 'Book', 'read', 4, 'Loved it', now() - interval '2 days'),
  ('cccccccc-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', '9789726084884', 'Book', 'read', 5, '', now()),
  ('dddddddd-0000-0000-0000-000000000001', '44444444-4444-4444-4444-444444444444', '9789726084884', 'Book', 'read', 1, 'hidden', now()),
  ('eeeeeeee-0000-0000-0000-000000000001', '55555555-5555-5555-5555-555555555555', '9789726084884', 'Book', 'read', 2, 'blocked', now()),
  ('ffffffff-0000-0000-0000-000000000001', '66666666-6666-6666-6666-666666666666', '9780441013593', 'Dune', 'read', 1, 'other book', now()),
  ('ffffffff-0000-0000-0000-000000000002', '66666666-6666-6666-6666-666666666666', '9789726084884', 'Book', 'to_read', null, '', now());

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', uid::text, 'role', 'authenticated')::text, true);
$$;

set local role authenticated;
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');

-- As ana: bruno (friend) and carla (public) count; dora (friends-only stranger), eve (blocked), fred (no score
-- or review for this book) and ana herself don't.
select results_eq(
  $$select ratings, average, five, four, three, two, one, reviews from book_score('9789726084884')$$,
  $$values (2, 4.50::numeric, 1, 1, 0, 0, 0, 1)$$,
  'score_counts_only_visible_readers_and_not_me');
select results_eq(
  $$select username, rating, review from book_reviews('9789726084884')$$,
  $$values ('bruno'::text, 4, 'Loved it'::text), ('carla'::text, 5, ''::text)$$,
  'reviews_written_first_then_scores');
select results_eq(
  $$select username from book_reviews('9789726084884', 1, 1)$$,
  $$values ('carla'::text)$$,
  'reviews_page_with_skip_and_max');
select results_eq(
  $$select ratings, reviews from book_score('0000000000000')$$,
  $$values (0, 0)$$,
  'unknown_isbn_has_no_score');
select is_empty($$select 1 from book_reviews(null)$$, 'no_isbn_no_reviews');

-- As bruno: ana (friend) counts now, and her own book isn't hidden from others.
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select results_eq(
  $$select username from book_reviews('9789726084884') order by username$$,
  $$values ('ana'::text), ('carla'::text), ('eve'::text)$$,
  'friend_and_public_readers_seen_by_bruno');

-- Signed out: nothing.
select set_config('request.jwt.claims', '', true);
select results_eq($$select ratings from book_score('9789726084884')$$, $$values (0)$$, 'signed_out_gets_no_score');
select is_empty($$select 1 from book_reviews('9789726084884')$$, 'signed_out_gets_no_reviews');

-- Not callable without signing in to the app at all.
set local role anon;
select throws_ok($$select * from book_reviews('9789726084884')$$, '42501', null, 'anon_cannot_call_book_reviews');
select throws_ok($$select * from book_score('9789726084884')$$, '42501', null, 'anon_cannot_call_book_score');

select * from finish();
rollback;
