-- A friend suggests a book to other friends. Each suggestion is one row per recipient, holding its own copy of what the
-- recipient needs to see, so it doesn't depend on the sender's library or on the book being shared.
-- Written and read only through the functions below.

create table book_suggestions (
  id bigint generated always as identity primary key,
  sender uuid not null references profiles (id) on delete cascade,
  recipient uuid not null references profiles (id) on delete cascade,
  -- The same book from the same person is suggested once, also after it was dismissed: no nagging.
  book_key text not null check (char_length(book_key) between 1 and 700),
  isbn text check (char_length(isbn) <= 20),
  title text not null check (char_length(title) between 1 and 300),
  authors text not null default '' check (char_length(authors) <= 300),
  cover_url text check (char_length(cover_url) <= 500),
  pages int check (pages between 1 and 20000),
  blurb text check (char_length(blurb) <= 600),
  note text not null default '' check (char_length(note) <= 280),
  created_at timestamptz not null default now(),
  -- The recipient has looked at it (the badge counts the unseen).
  seen_at timestamptz,
  -- The recipient added it to their library or dismissed it.
  closed_at timestamptz,
  check (sender <> recipient),
  unique (sender, recipient, book_key)
);
create index book_suggestions_inbox on book_suggestions (recipient, created_at desc, id desc) where closed_at is null;
create index book_suggestions_sent on book_suggestions (sender, created_at desc);

alter table book_suggestions enable row level security;
revoke all on book_suggestions from public, anon, authenticated;

-- [p_recipients] who are friends and not blocked either way get the book. Returns how many new suggestions were made:
-- anyone else, and anyone who already had this book from the caller, is skipped.
create function suggest_book(p_book jsonb, p_recipients uuid[], p_note text default '') returns int
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  me uuid := require_user();
  v_title text := btrim(coalesce(p_book ->> 'title', ''));
  v_authors text := btrim(coalesce(p_book ->> 'authors', ''));
  v_isbn text := nullif(btrim(coalesce(p_book ->> 'isbn', '')), '');
  v_cover text := allowed_cover_url(p_book ->> 'cover_url');
  v_blurb text := left(nullif(btrim(coalesce(p_book ->> 'blurb', '')), ''), 600);
  v_note text := btrim(coalesce(p_note, ''));
  v_pages int;
  v_key text;
  v_digits text := upper(regexp_replace(coalesce(v_isbn, ''), '[-[:space:]]', '', 'g'));
  v_wanted uuid[];
  v_today int;
  v_sent int;
begin
  if char_length(v_title) not between 1 and 300 or char_length(v_authors) > 300 or char_length(v_isbn) > 20
     or char_length(v_note) > 280 or coalesce(cardinality(p_recipients), 0) > 20 then
    raise exception 'invalid suggestion' using errcode = '22023';
  end if;
  begin
    v_pages := (p_book ->> 'pages')::int;
  exception when others then
    v_pages := null;
  end;
  if v_pages not between 1 and 20000 then
    v_pages := null;
  end if;
  -- An edition's ISBN-10 and ISBN-13 share these digits, and hyphens don't make it another book. Without an ISBN: the
  -- whole title, unlike merge_library's key (the volumes of a series are different books to suggest), hashed because
  -- folding can make a long title much longer.
  v_key := case
    when v_digits ~ '^[0-9]{9}[0-9X]$' then 'isbn:978' || left(v_digits, 9)
    when v_digits ~ '^97[89][0-9]{10}$' then 'isbn:' || left(v_digits, 12)
    when v_digits <> '' then 'isbn:' || v_digits
    else 'title:' || md5(btrim(_fold(v_title)) || '|' || btrim(_fold(split_part(v_authors, ',', 1))))
  end;

  -- One at a time per sender, so that the daily limit holds.
  perform pg_advisory_xact_lock(hashtext('suggestions:' || me::text));
  select coalesce(array_agg(distinct r), '{}') into v_wanted
  from unnest(coalesce(p_recipients, '{}')) r
  where r <> me and are_friends(me, r) and not is_blocked_with(r)
    and not exists (select 1 from book_suggestions s where s.sender = me and s.recipient = r and s.book_key = v_key);
  select count(*) into v_today from book_suggestions s
  where s.sender = me and s.created_at > now() - interval '1 day';
  if v_today + cardinality(v_wanted) > 100 then
    raise exception 'too many suggestions today' using errcode = 'P0429';
  end if;

  insert into book_suggestions (sender, recipient, book_key, isbn, title, authors, cover_url, pages, blurb, note)
  select me, r, v_key, v_isbn, v_title, v_authors, v_cover, v_pages, v_blurb, v_note from unnest(v_wanted) r
  on conflict (sender, recipient, book_key) do nothing;
  get diagnostics v_sent = row_count;
  return v_sent;
end;
$$;

-- What friends suggested to the caller and is still open, newest first. A block or an ended friendship hides it.
create function my_book_suggestions(max int default 50)
returns table (id bigint, sender_id uuid, username text, display_name text, avatar_path text, isbn text, title text,
  authors text, cover_url text, pages int, blurb text, note text, created_at timestamptz, seen boolean)
language sql stable security definer set search_path = public, pg_temp as $$
  with me as (select require_user() as id)
  select s.id, p.id, p.username, p.display_name, p.avatar_path, s.isbn, s.title, s.authors, s.cover_url, s.pages,
         s.blurb, s.note, s.created_at, s.seen_at is not null
  from book_suggestions s
  join profiles p on p.id = s.sender
  cross join me
  where s.recipient = me.id
    and s.closed_at is null
    and are_friends(me.id, s.sender)
    and not is_blocked_with(s.sender)
  order by s.created_at desc, s.id desc
  limit least(greatest(my_book_suggestions.max, 1), 100);
$$;

-- The caller has looked at these suggestions (the ones on their screen, not any that arrived since): the badge clears
-- on all their devices.
create function mark_book_suggestions_seen(ids bigint[]) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  update book_suggestions s set seen_at = now()
  where s.id = any (mark_book_suggestions_seen.ids)
    and s.recipient = require_user() and s.seen_at is null and s.closed_at is null;
end;
$$;

-- The caller added the book or dismissed it. Someone else's suggestion is left alone.
create function close_book_suggestion(suggestion bigint) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  update book_suggestions s set closed_at = now(), seen_at = coalesce(s.seen_at, now())
  where s.id = close_book_suggestion.suggestion and s.recipient = require_user() and s.closed_at is null;
end;
$$;

-- A block ends what's open between the two people, so that it doesn't return if they are friends again.
create function close_suggestions_on_block() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  update book_suggestions s set closed_at = now(), seen_at = coalesce(s.seen_at, now())
  where s.closed_at is null
    and ((s.sender = new.blocker and s.recipient = new.blocked) or (s.sender = new.blocked and s.recipient = new.blocker));
  return null;
end;
$$;
revoke all on function close_suggestions_on_block() from public, anon, authenticated;
create trigger blocks_close_suggestions after insert on blocks
  for each row execute function close_suggestions_on_block();

revoke all on function suggest_book(jsonb, uuid[], text), my_book_suggestions(int), mark_book_suggestions_seen(bigint[]),
  close_book_suggestion(bigint) from public, anon;
grant execute on function suggest_book(jsonb, uuid[], text), my_book_suggestions(int), mark_book_suggestions_seen(bigint[]),
  close_book_suggestion(bigint) to authenticated;
