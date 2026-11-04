-- MyBooks: community scores and reviews of a book, for the book page.
-- Readers are matched on the book's ISBN. Only readers the caller may see count (as in the feed: friends and
-- public profiles, never across a block); the caller is left out, as their own score and review are on the page.

-- The score summary: how many rated it, the average, how many gave each score, and how many wrote a review.
create function book_score(isbn text)
returns table (ratings int, average numeric, five int, four int, three int, two int, one int, reviews int)
language sql stable security definer set search_path = public as $$
  select count(b.rating)::int,
         round(avg(b.rating), 2),
         (count(*) filter (where b.rating = 5))::int,
         (count(*) filter (where b.rating = 4))::int,
         (count(*) filter (where b.rating = 3))::int,
         (count(*) filter (where b.rating = 2))::int,
         (count(*) filter (where b.rating = 1))::int,
         (count(*) filter (where b.review <> ''))::int
  from shared_books b
  where auth.uid() is not null
    and b.isbn = book_score.isbn
    and b.owner <> auth.uid()
    and can_see_activity_of(b.owner);
$$;

-- Readers' scores and reviews, those with a written review first, newest first; [skip] for "Show more".
create function book_reviews(isbn text, skip int default 0, max int default 10)
returns table (person_id uuid, username text, display_name text, avatar_path text,
  rating int, review text, status book_status, updated_at timestamptz)
language sql stable security definer set search_path = public as $$
  select p.id, p.username, p.display_name, p.avatar_path, b.rating, b.review, b.status, b.updated_at
  from shared_books b
  join profiles p on p.id = b.owner
  where auth.uid() is not null
    and b.isbn = book_reviews.isbn
    and b.owner <> auth.uid()
    and (b.rating is not null or b.review <> '')
    and can_see_activity_of(b.owner)
  order by (b.review <> '') desc, b.updated_at desc, p.username
  offset greatest(book_reviews.skip, 0)
  limit least(greatest(book_reviews.max, 1), 50);
$$;

revoke all on function book_score(text), book_reviews(text, int, int) from public, anon;
grant execute on function book_score(text), book_reviews(text, int, int) to authenticated;
