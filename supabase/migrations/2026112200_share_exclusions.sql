-- "Don't share this book", now the same on every device. Each excluded book is one synced preference in library_prefs:
-- id 'share_excluded.<book uuid>', value '1'; removing the exclusion deletes the row (the sync marker). One row per book,
-- so two devices excluding different books at the same time can't overwrite each other.
--
-- The server enforces it, so an app version that doesn't know about the setting can't put an excluded book back:
-- marking a book removes its public copy, and publish_book quietly ignores it (an error would make those apps retry).

-- By exact id only: two books that look alike (volumes of a series, duplicates) stay separate. A public copy under another
-- id than the book's uuid (a restored backup keeps its old one) is taken down by the phone that holds it.
create function _is_share_excluded(p_owner uuid, p_book uuid) returns boolean
language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1 from library_prefs p
    where p.owner = p_owner and p.id = 'share_excluded.' || p_book::text and not p.deleted and p.value = '1'
  );
$$;
revoke all on function _is_share_excluded(uuid, uuid) from public, anon, authenticated;

create function _unshare_excluded_book() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v_book uuid;
begin
  if new.id like 'share\_excluded.%' and not new.deleted and new.value = '1' then
    begin
      v_book := substr(new.id, char_length('share_excluded.') + 1)::uuid;
    exception when others then
      return new; -- Not a book id: nothing to take down, and a sync write must not fail over it.
    end;
    delete from shared_books s where s.id = v_book and s.owner = new.owner;
  end if;
  return new;
end;
$$;
revoke all on function _unshare_excluded_book() from public, anon, authenticated;

create trigger library_prefs_unshare_excluded
  after insert or update on library_prefs
  for each row execute function _unshare_excluded_book();

-- As before, with one addition: a book its owner excluded is ignored.
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
  -- The lock sync_push holds, so a publish can't slip in between an exclusion and its removal of the public copy.
  perform pg_advisory_xact_lock(hashtext('library:' || me::text));
  if _is_share_excluded(me, v_id) then
    return;
  end if;
  if v_pages not between 1 and 20000 then
    v_pages := null;
  end if;
  if v_current_page < 0 or v_current_page > v_pages then
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
    -- Only reading changes count as "updated" (it orders "Friends are reading"); a new blurb or title doesn't.
    updated_at = case
      when (s.status, s.rating, s.progress, s.review, s.current_page)
           is distinct from (excluded.status, excluded.rating, excluded.progress, excluded.review, excluded.current_page)
      then now() else s.updated_at end
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
