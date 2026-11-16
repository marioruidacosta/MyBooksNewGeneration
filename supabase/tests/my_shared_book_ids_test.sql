-- pgTAP tests for my_shared_book_ids: it returns only the caller's shared book ids, and signed-out callers are refused.
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
create function pg_temp.book(id text, title text) returns jsonb language sql as $$
  select jsonb_build_object('id', id, 'isbn', '9789726084884', 'title', title, 'authors', 'Author', 'status', 'to_read');
$$;
create function pg_temp.mine() returns uuid[] language sql as $$
  select coalesce(array_agg(id order by id), '{}') from my_shared_book_ids() id;
$$;

set local role authenticated;
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select publish_book(pg_temp.book('aaaaaaaa-0000-0000-0000-000000000001', 'A1'));
select publish_book(pg_temp.book('aaaaaaaa-0000-0000-0000-000000000002', 'A2'));
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select publish_book(pg_temp.book('bbbbbbbb-0000-0000-0000-000000000001', 'B1'));

select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select is(pg_temp.mine(), array['aaaaaaaa-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000002']::uuid[],
  'returns_only_my_ids: ana');
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');
select is(pg_temp.mine(), array['bbbbbbbb-0000-0000-0000-000000000001']::uuid[], 'returns_only_my_ids: bruno');

select pg_temp.act_as('11111111-1111-1111-1111-111111111111');
select unpublish_book('aaaaaaaa-0000-0000-0000-000000000002');
select is(pg_temp.mine(), array['aaaaaaaa-0000-0000-0000-000000000001']::uuid[], 'an unpublished book is no longer listed');

select pg_temp.act_as('33333333-3333-3333-3333-333333333333');
select is(pg_temp.mine(), '{}'::uuid[], 'nothing shared: empty');

-- Signed in without a user (no sub) is refused; anon has no execute right.
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);
select throws_ok($$select * from my_shared_book_ids()$$, '28000', null, 'anon_refused: no user');
reset role;
set local role anon;
select throws_ok($$select * from my_shared_book_ids()$$, '42501', null, 'anon_refused: no execute right');

reset role;
select * from finish();
rollback;
