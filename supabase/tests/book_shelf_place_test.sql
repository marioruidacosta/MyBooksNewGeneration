-- pgTAP tests for a book's place on a physical shelf: it syncs like any field and stays out of what is shared.
begin;
create extension if not exists pgtap with schema extensions;
select * from no_plan();

insert into auth.users (id, email, aud, role) values
  ('11111111-1111-1111-1111-111111111111', 'ana@test.local', 'authenticated', 'authenticated');

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', uid::text, 'role', 'authenticated')::text, true);
$$;
create function pg_temp.pulled(field text) returns text language sql as $$
  select sync_pull(0) -> 'rows' -> 0 -> 'fields' ->> field;
$$;

set local role authenticated;
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');

select sync_push('[{"table":"books","id":"b1",
  "fields":{"title":"Stoner","shelf_id":"s1","shelf_position":2.5},
  "times":{"title":100,"shelf_id":100,"shelf_position":100}}]');
select is(pg_temp.pulled('shelf_id'), 's1', 'the shelf comes back on pull');
select is(pg_temp.pulled('shelf_position'), '2.5', 'the position comes back on pull');

select sync_push('[{"table":"books","id":"b1","fields":{"shelf_id":"old"},"times":{"shelf_id":50}}]');
select is(pg_temp.pulled('shelf_id'), 's1', 'an older change does not win');

select sync_push('[{"table":"books","id":"b1","fields":{"shelf_id":"s2"},"times":{"shelf_id":200}}]');
select is(pg_temp.pulled('shelf_id'), 's2', 'a newer change wins');
select is(pg_temp.pulled('shelf_position'), '2.5', 'a client that sends only one field leaves the other alone');

select sync_push('[{"table":"books","id":"b2","fields":{"title":"Emma"},"times":{"title":100}}]');
select is(
  sync_push(jsonb_build_array(jsonb_build_object('table', 'books', 'id', 'b1',
    'fields', jsonb_build_object('shelf_id', repeat('x', 41)), 'times', '{"shelf_id":300}'::jsonb))) -> 'set_aside' -> 0 ->> 'id',
  'b1', 'a shelf id over 40 characters is set aside');
select is(pg_temp.pulled('shelf_id'), 's2', 'the refused value changes nothing');

reset role;
select hasnt_column('public', 'shared_books', 'shelf_id', 'a place is never part of a shared book');
select hasnt_column('public', 'shared_books', 'shelf_position', 'nor its position');

select * from finish();
rollback;
