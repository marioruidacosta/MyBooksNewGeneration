-- A book's place on a physical shelf (the shelves themselves are synced preferences, one per bookcase).
-- Only new columns: _merge_row and sync_pull read the table's columns, and no community table or function uses them.
alter table library_books
  add column shelf_id text check (char_length(shelf_id) <= 40),
  add column shelf_position double precision;
