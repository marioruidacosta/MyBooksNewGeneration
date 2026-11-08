-- BookNerd's friend suggestions: readers with similar taste (public readers who share activity and rated 4-5 stars a
-- book the caller rated 4-5 stars, matched by ISBN) and friends of the caller's friends. Never the caller, anyone
-- with a friendship row with the caller (accepted or pending, either way), or anyone blocked either way.

create or replace function suggested_readers(max int default 10)
returns table (id uuid, username text, display_name text, avatar_path text, shared_books int, mutual_friends int,
  mutual_names text[], top_title text)
language sql stable security definer set search_path = public, pg_temp as $$
  with me as (select require_user() as id),
  my_friends as (
    select case when f.requester = me.id then f.addressee else f.requester end as id
    from friendships f cross join me
    where f.status = 'accepted' and (f.requester = me.id or f.addressee = me.id)
  ),
  excluded as (
    select me.id from me
    union
    select case when f.requester = me.id then f.addressee else f.requester end
    from friendships f cross join me
    where f.requester = me.id or f.addressee = me.id
  ),
  loved as (
    select b.isbn from shared_books b cross join me
    where b.owner = me.id and b.rating >= 4 and b.isbn is not null
  ),
  taste as (
    select b.owner as id, count(distinct b.isbn)::int as shared_books,
           (array_agg(b.title order by b.updated_at desc))[1] as top_title
    from shared_books b
    join profiles p on p.id = b.owner
    where b.rating >= 4 and b.isbn in (select l.isbn from loved l)
      and p.visibility = 'public' and p.share_activity
    group by b.owner
  ),
  friends_of_friends as (
    select x.id, count(*)::int as mutual_friends,
           (array_agg(vp.display_name order by vp.display_name))[1:2] as mutual_names
    from (
      select case when f.requester = mf.id then f.addressee else f.requester end as id, mf.id as via
      from friendships f
      join my_friends mf on f.status = 'accepted' and (f.requester = mf.id or f.addressee = mf.id)
    ) x
    join profiles vp on vp.id = x.via
    group by x.id
  ),
  candidates as (
    select coalesce(t.id, f.id) as id,
           coalesce(t.shared_books, 0) as shared_books,
           coalesce(f.mutual_friends, 0) as mutual_friends,
           coalesce(f.mutual_names, '{}'::text[]) as mutual_names,
           t.top_title
    from taste t
    full outer join friends_of_friends f on f.id = t.id
  )
  select p.id, p.username, p.display_name, p.avatar_path, c.shared_books, c.mutual_friends, c.mutual_names, c.top_title
  from candidates c
  join profiles p on p.id = c.id
  where c.id not in (select e.id from excluded e)
    and not is_blocked_with(c.id)
  order by c.shared_books + 2 * c.mutual_friends desc, p.display_name
  limit least(greatest(suggested_readers.max, 1), 20);
$$;

revoke all on function suggested_readers(int) from public, anon;
grant execute on function suggested_readers(int) to authenticated;

-- The taste match looks up books rated 4-5 stars by ISBN; this keeps it from scanning every shared book.
create index if not exists shared_books_loved_isbn_idx on shared_books (isbn) where rating >= 4;
