-- Cloud library sync: the owner's books, quotes, reading challenges, ebook state, bookmarks and preferences, merged
-- per field by time. Rows are written only through sync_push (and merge_library); sync_pull reads them after a cursor.
-- A Realtime ping tells the owner's other devices to pull.

create sequence if not exists library_seq;

-- Every table: owner + id (the device-independent key), the synced fields as columns, field_times (field -> ms, set
-- by the server), deleted (a marker that reaches other devices) and seq (the pull cursor, from one shared sequence).
create table library_books (
  owner uuid not null references auth.users on delete cascade,
  id text not null,
  field_times jsonb not null default '{}',
  deleted boolean not null default false,
  seq bigint not null default nextval('library_seq'),
  isbn text,
  title text not null default '' check (char_length(title) <= 300),
  authors text not null default '',
  publisher text,
  published_year int,
  page_count int,
  cover_url text,
  description text,
  status text not null default '',
  rating int,
  notes text not null default '' check (char_length(notes) <= 20000),
  date_added bigint not null default 0,
  date_read bigint,
  date_started bigint,
  current_page int,
  progress_percent int,
  review text not null default '' check (char_length(review) <= 2000),
  tags text not null default '',
  series text,
  series_number text,
  lent_to text,
  lent_on bigint,
  primary key (owner, id)
);

create table library_quotes (
  owner uuid not null references auth.users on delete cascade,
  id text not null,
  field_times jsonb not null default '{}',
  deleted boolean not null default false,
  seq bigint not null default nextval('library_seq'),
  book_id text not null default '',
  text text not null default '' check (char_length(text) <= 5000),
  page int,
  date_added bigint not null default 0,
  position text,
  primary key (owner, id)
);

-- id is the year as text.
create table library_challenges (
  owner uuid not null references auth.users on delete cascade,
  id text not null,
  field_times jsonb not null default '{}',
  deleted boolean not null default false,
  seq bigint not null default nextval('library_seq'),
  goal int not null default 0,
  primary key (owner, id)
);

-- id is the book's uuid.
create table library_ebooks (
  owner uuid not null references auth.users on delete cascade,
  id text not null,
  field_times jsonb not null default '{}',
  deleted boolean not null default false,
  seq bigint not null default nextval('library_seq'),
  format text not null default '',
  file_name text not null default '',
  size bigint not null default 0,
  date_added bigint not null default 0,
  position text,
  last_opened bigint,
  primary key (owner, id)
);

create table library_bookmarks (
  owner uuid not null references auth.users on delete cascade,
  id text not null,
  field_times jsonb not null default '{}',
  deleted boolean not null default false,
  seq bigint not null default nextval('library_seq'),
  book_id text not null default '',
  position text not null default '',
  label text not null default '',
  date_added bigint not null default 0,
  primary key (owner, id)
);

-- id is the preference key.
create table library_prefs (
  owner uuid not null references auth.users on delete cascade,
  id text not null,
  field_times jsonb not null default '{}',
  deleted boolean not null default false,
  seq bigint not null default nextval('library_seq'),
  value text not null default '',
  primary key (owner, id)
);

create index library_books_owner_seq_idx on library_books (owner, seq);
create index library_quotes_owner_seq_idx on library_quotes (owner, seq);
create index library_challenges_owner_seq_idx on library_challenges (owner, seq);
create index library_ebooks_owner_seq_idx on library_ebooks (owner, seq);
create index library_bookmarks_owner_seq_idx on library_bookmarks (owner, seq);
create index library_prefs_owner_seq_idx on library_prefs (owner, seq);

-- Read-only for the owner; writes go through the functions below.
alter table library_books enable row level security;
alter table library_quotes enable row level security;
alter table library_challenges enable row level security;
alter table library_ebooks enable row level security;
alter table library_bookmarks enable row level security;
alter table library_prefs enable row level security;

create policy library_books_select on library_books for select to authenticated using (owner = auth.uid());
create policy library_quotes_select on library_quotes for select to authenticated using (owner = auth.uid());
create policy library_challenges_select on library_challenges for select to authenticated using (owner = auth.uid());
create policy library_ebooks_select on library_ebooks for select to authenticated using (owner = auth.uid());
create policy library_bookmarks_select on library_bookmarks for select to authenticated using (owner = auth.uid());
create policy library_prefs_select on library_prefs for select to authenticated using (owner = auth.uid());

revoke all on library_books, library_quotes, library_challenges, library_ebooks, library_bookmarks, library_prefs
  from public, anon, authenticated;
grant select on library_books, library_quotes, library_challenges, library_ebooks, library_bookmarks, library_prefs
  to authenticated;

-- Maps a change's table name to the real table; null for anything else.
create function _library_table(p_table text) returns text
language sql immutable set search_path = public, pg_temp as $$
  select case p_table
    when 'books' then 'library_books'
    when 'quotes' then 'library_quotes'
    when 'challenges' then 'library_challenges'
    when 'ebooks' then 'library_ebooks'
    when 'bookmarks' then 'library_bookmarks'
    when 'prefs' then 'library_prefs'
  end;
$$;
revoke all on function _library_table(text) from public, anon, authenticated;

-- Merges one row field by field: an incoming value replaces the stored one only when its time is newer. Times are
-- capped at now + 1 minute so a device whose clock runs ahead can't keep winning; an equal time keeps the stored
-- value. A field with no stored time (-1) accepts any incoming time. Unknown fields are ignored. Raises the usual
-- constraint errors for a bad value; the caller decides what to do with them. Used by sync_push and merge_library.
create function _merge_row(p_table text, p_owner uuid, p_id text, p_fields jsonb, p_times jsonb) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  tbl text := _library_table(p_table);
  stored jsonb;
  merged jsonb;
  ft jsonb;
  cols text[];
  f text;
  t bigint;
  cap bigint := floor(extract(epoch from now()) * 1000)::bigint + 60000;
  changed boolean := false;
begin
  if tbl is null then
    raise exception 'unknown table %', p_table using errcode = '22023';
  end if;
  if p_id is null or char_length(p_id) not between 1 and 100 then
    raise exception 'bad id' using errcode = '22023';
  end if;

  select array_agg(attname::text) into cols
  from pg_attribute
  where attrelid = ('public.' || tbl)::regclass and attnum > 0 and not attisdropped
    and attname not in ('owner', 'id', 'field_times', 'seq');

  execute format('select to_jsonb(t) from %I t where owner = $1 and id = $2', tbl) into stored using p_owner, p_id;
  if stored is null then
    -- A new row: insert the defaults, then merge into it like any stored row. Rolled back with the caller's block.
    execute format('insert into %I (owner, id) values ($1, $2) returning to_jsonb(%I.*)', tbl, tbl)
      into stored using p_owner, p_id;
    changed := true;
  end if;
  merged := stored;
  ft := stored->'field_times';

  for f in select jsonb_object_keys(coalesce(p_fields, '{}'::jsonb)) loop
    continue when not (f = any (cols)) or f = 'field_times';
    t := least(coalesce((p_times->>f)::bigint, 0), cap);
    if t > coalesce((ft->>f)::bigint, -1) then
      merged := jsonb_set(merged, array[f], p_fields->f);
      ft := jsonb_set(ft, array[f], to_jsonb(t));
      changed := true;
    end if;
  end loop;

  if changed then
    execute format(
      'update %I set field_times = $3, seq = nextval(''library_seq''), (%s) = (select %s from jsonb_populate_record(null::%I, $4) r) where owner = $1 and id = $2',
      tbl,
      (select string_agg(format('%I', c), ', ') from unnest(cols) c),
      (select string_agg(format('r.%I', c), ', ') from unnest(cols) c),
      tbl)
    using p_owner, p_id, ft, merged;
  end if;
end;
$$;
revoke all on function _merge_row(text, uuid, text, jsonb, jsonb) from public, anon, authenticated;

-- Tells the owner's devices to pull. Never fails the write if Realtime isn't there.
create function _library_ping(p_owner uuid) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  execute 'select realtime.send($1, $2, $3, $4)' using '{}'::jsonb, 'changed', 'library:' || p_owner, true;
exception when others then
  null;
end;
$$;
revoke all on function _library_ping(uuid) from public, anon, authenticated;

create function sync_push(changes jsonb) returns jsonb
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  me uuid := require_user();
  c jsonb;
  applied jsonb := '[]';
  set_aside jsonb := '[]';
begin
  for c in select value from jsonb_array_elements(coalesce(changes, '[]'::jsonb)) loop
    begin
      perform _merge_row(c->>'table', me, c->>'id', c->'fields', c->'times');
      applied := applied || to_jsonb(c->>'id');
    exception when check_violation or invalid_text_representation or string_data_right_truncation
        or numeric_value_out_of_range or not_null_violation or invalid_parameter_value
        or datetime_field_overflow or invalid_datetime_format then
      set_aside := set_aside || jsonb_build_object('id', c->>'id', 'reason', sqlerrm);
    end;
  end loop;
  if jsonb_array_length(applied) > 0 then
    perform _library_ping(me);
  end if;
  return jsonb_build_object('applied', applied, 'set_aside', set_aside);
end;
$$;

create function sync_pull(after bigint, max int default 500) returns jsonb
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
  me uuid := require_user();
  lim int := least(greatest(coalesce(sync_pull.max, 500), 1), 1000);
  rows jsonb;
begin
  select coalesce(jsonb_agg(r.j order by r.seq), '[]'::jsonb) into rows
  from (
    select u.seq, jsonb_build_object('table', u.tbl, 'id', u.id, 'fields', u.fields, 'times', u.field_times,
                                     'deleted', u.deleted, 'seq', u.seq) as j
    from (
      select 'books' as tbl, id, seq, field_times, deleted, to_jsonb(t) - 'owner' - 'id' - 'field_times' - 'deleted' - 'seq' as fields
        from library_books t where owner = me and seq > coalesce(after, 0)
      union all
      select 'quotes', id, seq, field_times, deleted, to_jsonb(t) - 'owner' - 'id' - 'field_times' - 'deleted' - 'seq'
        from library_quotes t where owner = me and seq > coalesce(after, 0)
      union all
      select 'challenges', id, seq, field_times, deleted, to_jsonb(t) - 'owner' - 'id' - 'field_times' - 'deleted' - 'seq'
        from library_challenges t where owner = me and seq > coalesce(after, 0)
      union all
      select 'ebooks', id, seq, field_times, deleted, to_jsonb(t) - 'owner' - 'id' - 'field_times' - 'deleted' - 'seq'
        from library_ebooks t where owner = me and seq > coalesce(after, 0)
      union all
      select 'bookmarks', id, seq, field_times, deleted, to_jsonb(t) - 'owner' - 'id' - 'field_times' - 'deleted' - 'seq'
        from library_bookmarks t where owner = me and seq > coalesce(after, 0)
      union all
      select 'prefs', id, seq, field_times, deleted, to_jsonb(t) - 'owner' - 'id' - 'field_times' - 'deleted' - 'seq'
        from library_prefs t where owner = me and seq > coalesce(after, 0)
    ) u
    order by u.seq
    limit lim
  ) r;
  return jsonb_build_object(
    'rows', rows,
    'last', coalesce((select max((x->>'seq')::bigint) from jsonb_array_elements(rows) x), coalesce(after, 0)));
end;
$$;

revoke all on function sync_push(jsonb) from public, anon;
grant execute on function sync_push(jsonb) to authenticated;
revoke all on function sync_pull(bigint, int) from public, anon;
grant execute on function sync_pull(bigint, int) to authenticated;

-- Only the owner may receive the pings on their private channel library:<owner>.
do $$
begin
  if to_regclass('realtime.messages') is not null then
    execute 'drop policy if exists library_owner_receives on realtime.messages';
    execute $p$create policy library_owner_receives on realtime.messages for select to authenticated
      using (realtime.topic() = 'library:' || (select auth.uid())::text)$p$;
  end if;
end;
$$;
