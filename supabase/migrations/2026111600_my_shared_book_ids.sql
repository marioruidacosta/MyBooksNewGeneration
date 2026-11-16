-- Which of my books are shared with the community. shared_books has no table access, so the website (another device
-- of the same account) asks here: it publishes a book only when its id is in this list, or the site just created it.

create function my_shared_book_ids() returns setof uuid
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  me uuid := require_user();
begin
  return query select s.id from shared_books s where s.owner = me;
end;
$$;

revoke all on function my_shared_book_ids() from public, anon;
grant execute on function my_shared_book_ids() to authenticated;
