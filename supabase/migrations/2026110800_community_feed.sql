-- The evolved community feed: each shared book also carries a short blurb, its page count and the current page
-- (returned with feed entries), and two functions show what friends are reading and which friends have a book.
-- Nothing new becomes visible: only friends who share activity, not blocked either way.

alter table shared_books
  add column blurb text check (char_length(blurb) <= 600),
  add column pages int check (pages between 1 and 20000),
  add column current_page int check (current_page >= 0);

-- The page a progress entry was made at ("is on page 182 of 344" stays true as the reader moves on).
alter table activities add column current_page int;

-- Same columns as before, the book's extras appended. A reviewed entry carries the book's score (for its stars);
-- an entry's page is the one it was made at.
create or replace view activity_entries as
  select a.id, a.kind, case when a.kind = 'reviewed' then b.rating else a.rating end as rating, a.progress, a.created_at,
         p.id as actor_id, p.username, p.display_name, p.avatar_path, p.visibility,
         b.id as book_id, b.isbn, b.title, b.authors, b.cover_url,
         case when a.kind = 'reviewed' then b.review end as review,
         (select count(*)::int from likes l where l.activity = a.id) as like_count,
         exists (select 1 from likes l where l.activity = a.id and l.liker = auth.uid()) as liked_by_me,
         b.blurb, b.pages, a.current_page
  from activities a
  join profiles p on p.id = a.actor
  join shared_books b on b.id = a.book;
revoke all on activity_entries from anon, authenticated;

-- Publishing: as before, plus blurb, pages and current page. They never decide activity. A blurb longer than 600
-- characters is cut and out-of-range pages are dropped, so an odd book is never rejected for them.
create or replace function publish_book(book jsonb, before jsonb default null) returns void
language plpgsql security definer set search_path = public as $$
declare
  me uuid := require_user();
  v_id uuid;
  v_status book_status;
  v_rating int;
  v_progress int;
  v_pages int;
  v_current_page int;
  v_title text := trim(coalesce(book ->> 'title', ''));
  v_authors text := coalesce(book ->> 'authors', '');
  v_isbn text := nullif(book ->> 'isbn', '');
  v_review text := coalesce(book ->> 'review', '');
  v_cover text := allowed_cover_url(book ->> 'cover_url');
  v_blurb text := left(nullif(trim(coalesce(book ->> 'blurb', '')), ''), 600);
  stored shared_books%rowtype;
  p_status book_status;
  p_rating int;
  p_progress int;
  p_review text := '';
  status_added boolean := false;
begin
  if (select share_activity from profiles where id = me) is not true then
    return;
  end if;

  begin
    v_id := (book ->> 'id')::uuid;
    v_status := (book ->> 'status')::book_status;
    v_rating := (book ->> 'rating')::int;
    v_progress := (book ->> 'progress')::int;
    v_pages := (book ->> 'pages')::int;
    v_current_page := (book ->> 'current_page')::int;
  exception when others then
    raise exception 'invalid book' using errcode = '22023';
  end;
  if v_id is null or v_status is null
     or char_length(v_title) not between 1 and 300 or char_length(v_authors) > 300 or char_length(v_isbn) > 20
     or v_rating not between 1 and 5 or v_progress not between 0 and 100 or char_length(v_review) > 2000 then
    raise exception 'invalid book' using errcode = '22023';
  end if;
  if v_pages not between 1 and 20000 then
    v_pages := null;
  end if;
  if v_current_page < 0 then
    v_current_page := null;
  end if;

  select * into stored from shared_books s where s.id = v_id for update;
  if found then
    if stored.owner <> me then
      raise exception 'book not available' using errcode = 'P0002';
    end if;
    p_status := stored.status;
    p_rating := stored.rating;
    p_progress := stored.progress;
    p_review := stored.review;
  elsif before is not null then
    begin
      p_status := (before ->> 'status')::book_status;
      p_rating := (before ->> 'rating')::int;
      p_progress := (before ->> 'progress')::int;
    exception when others then
      raise exception 'invalid before' using errcode = '22023';
    end;
    p_review := coalesce(before ->> 'review', '');
  end if;

  insert into shared_books as s (id, owner, isbn, title, authors, cover_url, status, rating, progress, review,
                                 blurb, pages, current_page, updated_at)
  values (v_id, me, v_isbn, v_title, v_authors, v_cover, v_status, v_rating, v_progress, v_review,
          v_blurb, v_pages, v_current_page, now())
  on conflict (id) do update set
    isbn = excluded.isbn, title = excluded.title, authors = excluded.authors, cover_url = excluded.cover_url,
    status = excluded.status, rating = excluded.rating, progress = excluded.progress, review = excluded.review,
    blurb = excluded.blurb, pages = excluded.pages, current_page = excluded.current_page,
    updated_at = now()
  where s.owner = me;

  if v_status is distinct from p_status then
    insert into activities (actor, book, kind) values (me, v_id, case v_status
      when 'reading' then 'started' when 'read' then 'finished' when 'to_read' then 'want_to_read' else 'want_to_buy'
    end::activity_kind);
    status_added := true;
  end if;
  if v_rating is not null and v_rating is distinct from p_rating then
    perform record_collapsible(me, v_id, 'rated', v_rating, null);
  end if;
  if v_progress is not null and v_progress is distinct from p_progress and v_status = 'reading' and not status_added then
    perform record_collapsible(me, v_id, 'progress', null, v_progress);
    -- The entry just added (or collapsed into) keeps the page it was made at.
    update activities set current_page = v_current_page
    where id = (select a.id from activities a where a.book = v_id and a.kind = 'progress'
                order by a.created_at desc, a.id desc limit 1);
  end if;
  if p_review = '' and v_review <> '' then
    insert into activities (actor, book, kind) values (me, v_id, 'reviewed');
  elsif p_review <> '' and v_review = '' then
    delete from activities a where a.book = v_id and a.kind = 'reviewed';
  end if;
end;
$$;

-- The feeds: the same columns as before, the book's extras appended (a changed return type needs drop + create).
drop function feed(text, timestamptz, bigint, int);
drop function person_activity(uuid, timestamptz, bigint, int);

create function feed(scope text, before_time timestamptz default null, before_id bigint default null, max int default 20)
returns table (id bigint, kind activity_kind, rating int, progress int, created_at timestamptz,
  actor_id uuid, username text, display_name text, avatar_path text,
  book_id uuid, isbn text, title text, authors text, cover_url text, review text,
  like_count int, liked_by_me boolean, book_blurb text, book_pages int, book_current_page int)
language sql stable security definer set search_path = public as $$
  select e.id, e.kind, e.rating, e.progress, e.created_at, e.actor_id, e.username, e.display_name, e.avatar_path,
         e.book_id, e.isbn, e.title, e.authors, e.cover_url, e.review, e.like_count, e.liked_by_me,
         e.blurb, e.pages, e.current_page
  from activity_entries e
  where auth.uid() is not null
    and case feed.scope
          when 'friends' then e.actor_id = auth.uid() or are_friends(auth.uid(), e.actor_id)
          when 'everyone' then e.visibility = 'public'
          else false
        end
    and not is_blocked_with(e.actor_id)
    and (feed.before_time is null
         or (e.created_at, e.id) < (feed.before_time, coalesce(feed.before_id, 9223372036854775807)))
  order by e.created_at desc, e.id desc
  limit least(greatest(feed.max, 1), 50);
$$;

create function person_activity(person uuid, before_time timestamptz default null, before_id bigint default null, max int default 20)
returns table (id bigint, kind activity_kind, rating int, progress int, created_at timestamptz,
  actor_id uuid, username text, display_name text, avatar_path text,
  book_id uuid, isbn text, title text, authors text, cover_url text, review text,
  like_count int, liked_by_me boolean, book_blurb text, book_pages int, book_current_page int)
language sql stable security definer set search_path = public as $$
  select e.id, e.kind, e.rating, e.progress, e.created_at, e.actor_id, e.username, e.display_name, e.avatar_path,
         e.book_id, e.isbn, e.title, e.authors, e.cover_url, e.review, e.like_count, e.liked_by_me,
         e.blurb, e.pages, e.current_page
  from activity_entries e
  where e.actor_id = person_activity.person
    and can_see_activity_of(person_activity.person)
    and (person_activity.before_time is null
         or (e.created_at, e.id) < (person_activity.before_time, coalesce(person_activity.before_id, 9223372036854775807)))
  order by e.created_at desc, e.id desc
  limit least(greatest(person_activity.max, 1), 50);
$$;

-- What the caller's friends are reading now: friends who share activity, not blocked either way; newest first.
create function friends_reading()
returns table (actor_id uuid, username text, display_name text, avatar_path text, book_id uuid, isbn text, title text,
  authors text, cover_url text, pages int, current_page int, progress int, updated_at timestamptz)
language sql stable security definer set search_path = public as $$
  with me as (select require_user() as id)
  select p.id, p.username, p.display_name, p.avatar_path, b.id, b.isbn, b.title, b.authors, b.cover_url,
         b.pages, b.current_page, b.progress, b.updated_at
  from shared_books b
  join profiles p on p.id = b.owner
  cross join me
  where b.status = 'reading'
    and b.owner <> me.id
    and are_friends(me.id, b.owner)
    and p.share_activity
    and not is_blocked_with(b.owner)
  order by b.updated_at desc, b.id
  limit 20;
$$;

-- The caller's friends who share a book with this ISBN: read first, then reading, to read, to buy; then by name.
create function friends_with_book(p_isbn text)
returns table (actor_id uuid, username text, display_name text, avatar_path text, status book_status, rating int,
  progress int, review text)
language sql stable security definer set search_path = public as $$
  with me as (select require_user() as id)
  select p.id, p.username, p.display_name, p.avatar_path, b.status, b.rating, b.progress, left(b.review, 200)
  from shared_books b
  join profiles p on p.id = b.owner
  cross join me
  where b.isbn = p_isbn
    and b.owner <> me.id
    and are_friends(me.id, b.owner)
    and p.share_activity
    and not is_blocked_with(b.owner)
  order by case b.status when 'read' then 0 when 'reading' then 1 when 'to_read' then 2 else 3 end, p.display_name;
$$;

revoke all on function feed(text, timestamptz, bigint, int), person_activity(uuid, timestamptz, bigint, int),
  friends_reading(), friends_with_book(text) from public, anon;
grant execute on function feed(text, timestamptz, bigint, int), person_activity(uuid, timestamptz, bigint, int),
  friends_reading(), friends_with_book(text) to authenticated;
