-- MyBooks: "Readers like you loved" suggestions.
-- The phone sends the ISBNs of the reader's favourites (at most 50, never the whole library). Readers the caller may
-- see (friends and public profiles, never across a block; not the caller) who rated one of them 4–5 stars are "fans";
-- their other books rated 4–5 stars are suggested, those loved by the most fans first.

create function community_suggestions(liked text[], max int default 30)
returns table (isbn text, title text, authors text, cover_url text, readers int, because text)
language sql stable security definer set search_path = public as $$
  with mine as (
    select unnest(community_suggestions.liked[1:50]) as isbn
  ),
  fans as (
    select distinct b.owner, b.isbn as liked_isbn
    from shared_books b
    join mine m on m.isbn = b.isbn
    where auth.uid() is not null
      and b.rating >= 4
      and b.owner <> auth.uid()
      and can_see_activity_of(b.owner)
  ),
  picks as (
    select o.isbn, o.title, o.authors, o.cover_url, o.owner, min(f.liked_isbn) as because
    from shared_books o
    join fans f on f.owner = o.owner
    where o.rating >= 4
      and o.isbn is not null
      and o.isbn not in (select m.isbn from mine m)
    group by o.isbn, o.title, o.authors, o.cover_url, o.owner
  )
  select p.isbn,
         (array_agg(p.title order by p.title))[1],
         (array_agg(p.authors order by p.title))[1],
         allowed_cover_url((array_agg(p.cover_url order by p.cover_url nulls last))[1]),
         count(distinct p.owner)::int,
         min(p.because)
  from picks p
  group by p.isbn
  order by count(distinct p.owner) desc, p.isbn
  limit least(greatest(community_suggestions.max, 1), 50);
$$;

revoke all on function community_suggestions(text[], int) from public, anon;
grant execute on function community_suggestions(text[], int) to authenticated;
