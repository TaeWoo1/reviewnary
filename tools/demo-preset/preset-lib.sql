-- tools/demo-preset/preset-lib.sql — the dev-only archive schema behind the FRESH ↔ CONNECTED demo presets.
--
-- WHY A SCHEMA AND NOT A FILE. `fresh` has to remove the demo org's channel connection state, and
-- `connected` has to put it back byte-identically — including the ENCRYPTED credential row. Exporting
-- ciphertext to a file on disk would move credential material out of the database trust boundary for no
-- benefit, so the archive stays INSIDE Postgres: every archived byte travels DB → DB inside a single SQL
-- statement and never passes through the shell, a log line, or a file.
--
-- NOT A FLYWAY MIGRATION ON PURPOSE. This is local/dev demo tooling, not product schema. It lives in its
-- own `demo_preset` schema, is created on demand by tools/demo-preset/demo-preset.sh, and `public` is
-- never altered. Flyway neither knows nor cares about it; dropping the schema loses only the archive.
--
-- SCOPE. Every function is keyed by (org, channel) — the archive holds exactly one slot per pair, so a
-- second snapshot of the same pair replaces the first rather than accumulating. Nothing here deletes from
-- `public`; the delete set lives in the script, where it is printed before it runs.

-- The whole file is idempotent (create-if-not-exists / create-or-replace), so re-running it emits a wall of
-- "already exists, skipping" notices that hide the operator-facing output. Warnings and errors still show.
set client_min_messages = warning;

create schema if not exists demo_preset;

comment on schema demo_preset is
    'Local/dev demo-preset archive (tools/demo-preset). Not product schema; not managed by Flyway.';

-- Create the archive twin of a public table on first use. Plain LIKE: column definitions only, so the
-- archive carries no primary key, no unique index and no foreign key — several snapshots of related rows
-- can sit here in any order, and an archived row can never be mistaken for a live one.
create or replace function demo_preset.ensure(tbl text) returns void
language plpgsql as $$
begin
    execute format('create table if not exists demo_preset.%I (like public.%I)', 'a_' || tbl, tbl);
    execute format('alter table demo_preset.%I add column if not exists preset_org uuid', 'a_' || tbl);
    execute format('alter table demo_preset.%I add column if not exists preset_channel uuid', 'a_' || tbl);
    execute format('alter table demo_preset.%I add column if not exists archived_at timestamptz', 'a_' || tbl);
end $$;

-- Copy the rows matching `pred` into the archive slot for (org, channel), replacing whatever that slot
-- held. Returns the number of rows archived. `pred` is composed by the script, never by user input.
create or replace function demo_preset.snap(tbl text, org uuid, ch uuid, pred text) returns bigint
language plpgsql as $$
declare
    n    bigint;
    cols text;
begin
    perform demo_preset.ensure(tbl);
    execute format('delete from demo_preset.%I where preset_org = %L and preset_channel = %L',
                   'a_' || tbl, org, ch);
    select string_agg(quote_ident(column_name), ', ' order by ordinal_position) into cols
      from information_schema.columns
     where table_schema = 'public' and table_name = tbl;
    execute format(
        'insert into demo_preset.%I (%s, preset_org, preset_channel, archived_at) '
        || 'select %s, %L, %L, now() from public.%I where %s',
        'a_' || tbl, cols, cols, org, ch, tbl, pred);
    get diagnostics n = row_count;
    return n;
end $$;

-- Put the archived rows back, verbatim, under their original primary keys. `on conflict do nothing` makes
-- a restore idempotent: re-running `connected` over an already-restored state is a no-op, never a
-- duplicate-key failure. Returns the number of rows actually inserted.
create or replace function demo_preset.restore(tbl text, org uuid, ch uuid) returns bigint
language plpgsql as $$
declare
    n    bigint;
    cols text;
begin
    perform demo_preset.ensure(tbl);
    select string_agg(quote_ident(column_name), ', ' order by ordinal_position) into cols
      from information_schema.columns
     where table_schema = 'public' and table_name = tbl;
    execute format(
        'insert into public.%I (%s) select %s from demo_preset.%I '
        || 'where preset_org = %L and preset_channel = %L on conflict do nothing',
        tbl, cols, cols, 'a_' || tbl, org, ch);
    get diagnostics n = row_count;
    return n;
end $$;

-- How many rows the archive slot holds for a table (0 when the twin does not exist yet).
create or replace function demo_preset.archived_count(tbl text, org uuid, ch uuid) returns bigint
language plpgsql as $$
declare n bigint;
begin
    perform demo_preset.ensure(tbl);
    execute format('select count(*) from demo_preset.%I where preset_org = %L and preset_channel = %L',
                   'a_' || tbl, org, ch)
       into n;
    return n;
end $$;

-- The catalog `channels.status` value at snapshot time. The catalog row is global (not org-scoped), so it
-- is archived as a single value rather than a row copy; `fresh` lowers it to AVAILABLE and `connected`
-- puts the snapshotted value back.
create table if not exists demo_preset.a_channel_status (
    preset_org     uuid        not null,
    preset_channel uuid        not null,
    channel_status varchar(40) not null,
    archived_at    timestamptz not null,
    primary key (preset_org, preset_channel)
);
