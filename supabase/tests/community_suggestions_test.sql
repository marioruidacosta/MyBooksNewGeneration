-- pgTAP tests for "Readers like you loved" suggestions.
begin;
create extension if not exists pgtap with schema extensions;
select * from no_plan();

-- ana asks. bruno (her friend) and carla (public) loved her favourite F; dora (friends-only stranger) and eve (public,
-- but blocked ana) did too. fred (public) loved only another book.
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

-- F = 9780000000001 (ana's favourite); X, Y, Z, W = other books.
insert into shared_books (id, owner, isbn, title, authors, cover_url, status, rating) values
  ('bbbbbbbb-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', '9780000000001', 'F', 'A', null, 'read', 5),
  ('bbbbbbbb-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', '9780000000002', 'X', 'Ax', 'https://covers.openlibrary.org/b/id/1-M.jpg', 'read', 4),
  ('bbbbbbbb-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222', '9780000000003', 'Y', 'Ay', 'https://evil.example/x.jpg', 'read', 5),
  ('bbbbbbbb-0000-0000-0000-000000000004', '22222222-2222-2222-2222-222222222222', '9780000000005', 'W', 'Aw', null, 'read', 3),
  ('cccccccc-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', '9780000000001', 'F', 'A', null, 'read', 4),
  ('cccccccc-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333', '9780000000002', 'X', 'Ax', null, 'read', 5),
  ('dddddddd-0000-0000-0000-000000000001', '44444444-4444-4444-4444-444444444444', '9780000000001', 'F', 'A', null, 'read', 5),
  ('dddddddd-0000-0000-0000-000000000002', '44444444-4444-4444-4444-444444444444', '9780000000004', 'Z', 'Az', null, 'read', 5),
  ('eeeeeeee-0000-0000-0000-000000000001', '55555555-5555-5555-5555-555555555555', '9780000000001', 'F', 'A', null, 'read', 5),
  ('eeeeeeee-0000-0000-0000-000000000002', '55555555-5555-5555-5555-555555555555', '9780000000004', 'Z', 'Az', null, 'read', 5),
  ('ffffffff-0000-0000-0000-000000000001', '66666666-6666-6666-6666-666666666666', '9780000000009', 'Other', 'O', null, 'read', 5),
  ('ffffffff-0000-0000-0000-000000000002', '66666666-6666-6666-6666-666666666666', '9780000000004', 'Z', 'Az', null, 'read', 5);

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', uid::text, 'role', 'authenticated')::text, true);
$$;

set local role authenticated;
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');

-- X: loved by bruno and carla (2); Y: bruno (1). Not W (3 stars), not Z (only fans ana can't see, or no fan of F),
-- not F itself. Y's cover isn't from a book catalogue, so it's dropped.
select results_eq(
  $$select isbn, title, readers, because, cover_url from community_suggestions(array['9780000000001'])$$,
  $$values ('9780000000002'::text, 'X'::text, 2, '9780000000001'::text, 'https://covers.openlibrary.org/b/id/1-M.jpg'::text),
           ('9780000000003'::text, 'Y'::text, 1, '9780000000001'::text, null::text)$$,
  'suggests_what_visible_fans_loved_most_first');
select results_eq(
  $$select isbn from community_suggestions(array['9780000000001'], 1)$$,
  $$values ('9780000000002'::text)$$,
  'max_limits_the_list');
select is_empty($$select 1 from community_suggestions(array['9780000000077'])$$, 'no_fans_no_suggestions');
select is_empty($$select 1 from community_suggestions(array[]::text[])$$, 'no_favourites_no_suggestions');

-- Signed out: nothing; without signing in to the app at all: not callable.
select set_config('request.jwt.claims', '', true);
select is_empty($$select 1 from community_suggestions(array['9780000000001'])$$, 'signed_out_gets_nothing');
set local role anon;
select throws_ok($$select * from community_suggestions(array['9780000000001'])$$, '42501', null, 'anon_cannot_call');

select * from finish();
rollback;
