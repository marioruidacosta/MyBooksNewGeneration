-- pgTAP tests for the evolved community feed: blurb, pages and current page; friends reading; friends with a book.
begin;
create extension if not exists pgtap with schema extensions;
select * from no_plan();

-- ana is the reader. bruno, carla, dora and fred are her friends; carla doesn't share activity; dora blocked ana.
-- eve is a public stranger.
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
  ('33333333-3333-3333-3333-333333333333', 'carla', 'Carla', 'friends'),
  ('44444444-4444-4444-4444-444444444444', 'dora', 'Dora', 'friends'),
  ('55555555-5555-5555-5555-555555555555', 'eve', 'Eve', 'public'),
  ('66666666-6666-6666-6666-666666666666', 'fred', 'Fred', 'friends');
update profiles set share_activity = false where id = '33333333-3333-3333-3333-333333333333';
insert into friendships (requester, addressee, status) values
  ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'accepted'),
  ('11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', 'accepted'),
  ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', 'accepted'),
  ('66666666-6666-6666-6666-666666666666', '11111111-1111-1111-1111-111111111111', 'accepted');
insert into blocks (blocker, blocked) values
  ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111');

insert into shared_books (id, owner, isbn, title, status, rating, progress, review, pages, current_page, updated_at) values
  ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '9789722036504', 'Mine', 'reading', null, 5, '', null, null, now()),
  ('bbbbbbbb-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', '9789722036504', 'A Relíquia', 'reading', null, 40, '', 344, 137, now() - interval '1 hour'),
  ('bbbbbbbb-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', '9789722039994', 'Os Maias', 'read', 5, 100, repeat('x', 250), null, null, now() - interval '3 days'),
  ('cccccccc-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', '9789722039994', 'Os Maias', 'reading', null, 10, '', null, null, now()),
  ('dddddddd-0000-0000-0000-000000000001', '44444444-4444-4444-4444-444444444444', '9789722039994', 'Os Maias', 'reading', null, 10, '', null, null, now()),
  ('eeeeeeee-0000-0000-0000-000000000001', '55555555-5555-5555-5555-555555555555', '9789722039994', 'Os Maias', 'reading', null, 10, '', null, null, now()),
  ('ffffffff-0000-0000-0000-000000000001', '66666666-6666-6666-6666-666666666666', '9789722039994', 'Os Maias', 'to_buy', null, null, '', null, null, now()),
  ('ffffffff-0000-0000-0000-000000000002', '66666666-6666-6666-6666-666666666666', '9780441013593', 'Dune', 'reading', null, 10, '', null, null, now() - interval '2 hours');

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', uid::text, 'role', 'authenticated')::text, true);
$$;

set local role authenticated;

-- Publishing: blurb, pages and current page are stored; alone, they create no activity; out-of-range values are
-- dropped, a long blurb is cut, nothing is rejected.
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select lives_ok($$select publish_book('{"id":"bbbbbbbb-0000-0000-0000-000000000003","title":"Memorial do Convento",
  "authors":"José Saramago","status":"reading","progress":20,"review":"","blurb":"Baltasar e Blimunda.","pages":464,
  "current_page":93}'::jsonb)$$, 'publish_with_blurb_and_pages');
select results_eq(
  $$select book_blurb, book_pages, book_current_page from feed('friends') where book_id = 'bbbbbbbb-0000-0000-0000-000000000003'$$,
  $$values ('Baltasar e Blimunda.'::text, 464, null::int)$$,
  'blurb_and_pages_returned_by_feed_no_page_on_started');
select is((select count(*)::int from feed('friends') where book_id = 'bbbbbbbb-0000-0000-0000-000000000003'), 1, 'started_once');
select lives_ok($$select publish_book('{"id":"bbbbbbbb-0000-0000-0000-000000000003","title":"Memorial do Convento",
  "authors":"José Saramago","status":"reading","progress":20,"review":"","blurb":"Outra sinopse.","pages":465,
  "current_page":93}'::jsonb)$$, 'publish_new_blurb');
select is((select count(*)::int from feed('friends') where book_id = 'bbbbbbbb-0000-0000-0000-000000000003'), 1,
  'blurb_or_pages_alone_create_no_activity');
select lives_ok(format($$select publish_book('{"id":"bbbbbbbb-0000-0000-0000-000000000003","title":"Memorial do Convento",
  "status":"reading","progress":20,"blurb":"%s","pages":0,"current_page":-3}'::jsonb)$$, repeat('y', 700)),
  'odd_values_are_not_rejected');
select results_eq(
  $$select char_length(book_blurb), book_pages, book_current_page from feed('friends') where book_id = 'bbbbbbbb-0000-0000-0000-000000000003'$$,
  $$values (600, null::int, null::int)$$,
  'long_blurb_cut_and_out_of_range_dropped');


-- A progress entry keeps the page it was made at, whatever page the book is on later.
select lives_ok($$select publish_book('{"id":"bbbbbbbb-0000-0000-0000-000000000004","title":"Os Maias","status":"reading",
  "progress":10,"pages":600,"current_page":60}'::jsonb)$$, 'publish_started');
select lives_ok($$select publish_book('{"id":"bbbbbbbb-0000-0000-0000-000000000004","title":"Os Maias","status":"reading",
  "progress":20,"pages":600,"current_page":120}'::jsonb)$$, 'publish_progress');
reset role;
update shared_books set current_page = 300, progress = 50 where id = 'bbbbbbbb-0000-0000-0000-000000000004';
set local role authenticated;
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select results_eq(
  $$select progress, book_current_page from feed('friends') where book_id = 'bbbbbbbb-0000-0000-0000-000000000004' and kind = 'progress'$$,
  $$values (20, 120)$$,
  'progress_entry_keeps_its_page');

-- Reviewed entries carry the book's score (for their stars).
select lives_ok($$select publish_book('{"id":"bbbbbbbb-0000-0000-0000-000000000005","title":"Ensaio","status":"read",
  "rating":4,"review":"Bom."}'::jsonb)$$, 'publish_review');
select results_eq(
  $$select rating from feed('friends') where book_id = 'bbbbbbbb-0000-0000-0000-000000000005' and kind = 'reviewed'$$,
  $$values (4)$$,
  'reviewed_entries_carry_the_rating');
-- Older apps: feed's first columns are unchanged.
select lives_ok($$select id, kind, rating, progress, created_at, actor_id, username, display_name, avatar_path, book_id,
  isbn, title, authors, cover_url, review, like_count, liked_by_me from feed('friends')$$, 'old_feed_columns_unchanged');
select lives_ok($$select id, kind, book_blurb, book_pages, book_current_page from person_activity('22222222-2222-2222-2222-222222222222')$$,
  'person_activity_returns_book_extras');

-- As ana: friends reading.
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select results_eq(
  $$select username, title, pages, current_page, progress from friends_reading()$$,
  $$values ('bruno'::text, 'Memorial do Convento'::text, null::int, null::int, 20),
           ('bruno'::text, 'A Relíquia'::text, 344, 137, 40),
           ('fred'::text, 'Dune'::text, null::int, null::int, 10)$$,
  'friends_reading_newest_first');
select is_empty(
  $$select 1 from friends_reading() where username in ('carla', 'dora', 'eve', 'ana')$$,
  'friends_reading_hides_non_sharing_blocked_strangers_and_me');

-- Friends with a book: read, reading, to read, to buy; reviews cut at 200 characters.
select results_eq(
  $$select username, status::text, rating, char_length(review) from friends_with_book('9789722039994')$$,
  $$values ('bruno'::text, 'read'::text, 5, 200), ('fred'::text, 'to_buy'::text, null::int, 0)$$,
  'friends_with_book_read_first_and_only_visible_friends');
select is_empty($$select 1 from friends_with_book(null)$$, 'no_isbn_no_friends');

-- At most 20 in the row.
reset role;
insert into shared_books (id, owner, title, status, updated_at)
  select gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Old ' || n, 'reading', now() - interval '1 day' - n * interval '1 minute'
  from generate_series(1, 25) n;
set local role authenticated;
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select is((select count(*)::int from friends_reading()), 20, 'friends_reading_at_most_20');

-- A friend who stops sharing, or blocks me, disappears at once.
reset role;
update profiles set share_activity = false where id = '66666666-6666-6666-6666-666666666666';
insert into blocks (blocker, blocked) values ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111');
set local role authenticated;
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select is_empty($$select 1 from friends_reading()$$, 'friends_reading_hides_non_sharing_and_blocked');
select is_empty($$select 1 from friends_with_book('9789722039994')$$, 'friends_with_book_hides_non_sharing_and_blocked');

-- Not callable without signing in to the app.
set local role anon;
select throws_ok($$select * from friends_reading()$$, '42501', null, 'anon_cannot_call_friends_reading');
select throws_ok($$select * from friends_with_book('9789722039994')$$, '42501', null, 'anon_cannot_call_friends_with_book');

select * from finish();
rollback;
