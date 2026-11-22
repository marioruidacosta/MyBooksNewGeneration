-- pgTAP tests for book suggestions: friends only, one per book, private to the recipient, and stopped by a block.
begin;
create extension if not exists pgtap with schema extensions;
select * from no_plan();

insert into auth.users (id, email, aud, role) values
  ('11111111-1111-1111-1111-111111111111', 'ana@test.local', 'authenticated', 'authenticated'),
  ('22222222-2222-2222-2222-222222222222', 'bruno@test.local', 'authenticated', 'authenticated'),
  ('33333333-3333-3333-3333-333333333333', 'carla@test.local', 'authenticated', 'authenticated');
insert into profiles (id, username, display_name, visibility) values
  ('11111111-1111-1111-1111-111111111111', 'ana', 'Ana', 'friends'),
  ('22222222-2222-2222-2222-222222222222', 'bruno', 'Bruno', 'friends'),
  ('33333333-3333-3333-3333-333333333333', 'carla', 'Carla', 'friends');

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', uid::text, 'role', 'authenticated')::text, true);
$$;
create function pg_temp.book(title text, isbn text default null) returns jsonb language sql as $$
  select jsonb_build_object('title', title, 'authors', 'John Williams', 'isbn', isbn, 'pages', 288);
$$;
create function pg_temp.inbox() returns bigint language sql as $$
  select count(*) from my_book_suggestions();
$$;

set local role authenticated;

-- Ana and Bruno are friends; Carla is nobody's friend.
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select send_friend_request('22222222-2222-2222-2222-222222222222');
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select respond_to_friend_request('11111111-1111-1111-1111-111111111111', true);

select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select is(
  suggest_book(pg_temp.book('Stoner', '9781590171998'),
    array['22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333']::uuid[], '  Read it  '),
  1, 'only_friends: the friend gets it, the stranger does not');
select is(
  suggest_book(pg_temp.book('Stoner', '9781590171998'), array['22222222-2222-2222-2222-222222222222']::uuid[]),
  0, 'once_per_book: the same book is not suggested twice');
select is(pg_temp.inbox(), 0::bigint, 'the sender has nothing in their own inbox');
select throws_ok(
  $$select suggest_book(pg_temp.book('Long note'), array['22222222-2222-2222-2222-222222222222']::uuid[], repeat('x', 281))$$,
  '22023', null, 'limits: a note over 280 characters');
select throws_ok(
  $$select suggest_book(pg_temp.book('Crowd'), array(select gen_random_uuid() from generate_series(1, 21)))$$,
  '22023', null, 'limits: more than 20 friends');
select throws_ok($$select suggest_book('{"title": ""}'::jsonb, '{}'::uuid[])$$, '22023', null, 'limits: a book needs a title');
select throws_ok($$select * from book_suggestions$$, '42501', null, 'private: the table cannot be read directly');

select pg_temp.act_as('33333333-3333-3333-3333-333333333333');
select is(pg_temp.inbox(), 0::bigint, 'only_friends: the stranger received nothing');

select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select is(pg_temp.inbox(), 1::bigint, 'the friend received it');
select is((select note from my_book_suggestions()), 'Read it', 'the note arrives trimmed');
select is((select username from my_book_suggestions()), 'ana', 'it says who suggested it');
select is((select seen from my_book_suggestions()), false, 'it starts as new');
select mark_book_suggestions_seen(array(select id from my_book_suggestions()));
select is((select seen from my_book_suggestions()), true, 'looking at it marks it seen');
select set_config('test.suggestion', (select id::text from my_book_suggestions()), true);

select pg_temp.act_as('33333333-3333-3333-3333-333333333333');
select close_book_suggestion(current_setting('test.suggestion')::bigint);
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select is(pg_temp.inbox(), 1::bigint, 'someone else cannot close it');
select close_book_suggestion(current_setting('test.suggestion')::bigint);
select is(pg_temp.inbox(), 0::bigint, 'closing it removes it');

select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select is(
  suggest_book(pg_temp.book('Stoner', '9781590171998'), array['22222222-2222-2222-2222-222222222222']::uuid[]),
  0, 'no_nagging: a dismissed book cannot be suggested again');
select is(
  suggest_book(pg_temp.book('Emma'), array['22222222-2222-2222-2222-222222222222']::uuid[]),
  1, 'another book can be suggested');

select is(
  suggest_book(pg_temp.book('Stoner', '978-1-59017-199-8'), array['22222222-2222-2222-2222-222222222222']::uuid[]),
  0, 'once_per_book: a hyphenated ISBN is the same book');
select is(
  suggest_book(pg_temp.book('Stoner', '1590171993'), array['22222222-2222-2222-2222-222222222222']::uuid[]),
  0, 'once_per_book: its ISBN-10 is the same book');
select is(
  suggest_book(pg_temp.book(repeat('한', 233)), array['22222222-2222-2222-2222-222222222222']::uuid[]),
  1, 'a long title in Korean can be suggested');

select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select is(pg_temp.inbox(), 2::bigint, 'the other books arrived');
select mark_book_suggestions_seen(array(select id from my_book_suggestions() where title = 'Emma'));
select is((select count(*) from my_book_suggestions() where not seen), 1::bigint, 'only_shown: only the ones listed are marked seen');
select block_user('11111111-1111-1111-1111-111111111111');
select is(pg_temp.inbox(), 0::bigint, 'blocked: a block hides what they suggested');
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select is(
  suggest_book(pg_temp.book('Persuasion'), array['22222222-2222-2222-2222-222222222222']::uuid[]),
  0, 'blocked: nothing more can be suggested');

-- Unblocked and friends again: what was hidden does not come back.
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
delete from blocks where blocker = '22222222-2222-2222-2222-222222222222' and blocked = '11111111-1111-1111-1111-111111111111';
select send_friend_request('11111111-1111-1111-1111-111111111111');
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select respond_to_friend_request('22222222-2222-2222-2222-222222222222', true);
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select is(pg_temp.inbox(), 0::bigint, 'blocked: an old suggestion does not return after unblocking');

-- Signed in without a user (no sub) is refused; anon has no execute right.
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);
select throws_ok($$select * from my_book_suggestions()$$, '28000', null, 'anon_refused: no user');
reset role;
set local role anon;
select throws_ok($$select * from my_book_suggestions()$$, '42501', null, 'anon_refused: no execute right');

reset role;
select * from finish();
rollback;
