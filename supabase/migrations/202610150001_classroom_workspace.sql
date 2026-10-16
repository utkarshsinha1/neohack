create or replace function public.is_valid_workspace_path(p_path text)
returns boolean
language sql
immutable
strict
set search_path = pg_catalog
as $$
    select length(p_path) between 1 and 1024
       and p_path !~ '(^/|/$|//|\\|[[:cntrl:]])'
       and not exists (
           select 1
           from unnest(string_to_array(p_path, '/')) as path_segments(segment)
           where btrim(segment) = '' or segment = '.' or segment = '..'
              or length(segment) > 255
       );
$$;

create table if not exists public.classroom_teachers (
    user_id uuid primary key references auth.users(id) on delete cascade,
    provisioned_at timestamptz not null default now()
);

create table if not exists public.classrooms (
    id uuid primary key default gen_random_uuid(),
    classroom_name text not null check (
        length(btrim(classroom_name)) between 1 and 100
    ),
    join_code text not null unique check (join_code ~ '^[A-HJ-NP-Z2-9]{5}$'),
    teacher_id uuid not null references auth.users(id) on delete cascade,
    is_active boolean not null default true,
    created_at timestamptz not null default now()
);

create table if not exists public.classroom_members (
    classroom_id uuid not null references public.classrooms(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    display_name text not null check (length(btrim(display_name)) between 1 and 100),
    registration_number text check (
        registration_number is null or length(registration_number) <= 100
    ),
    joined_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    primary key (classroom_id, user_id)
);

create table if not exists public.classroom_files (
    id uuid primary key default gen_random_uuid(),
    classroom_id uuid not null references public.classrooms(id) on delete cascade,
    file_path text not null check (public.is_valid_workspace_path(file_path)),
    content text not null default '',
    language text not null default 'text',
    version bigint not null default 1 check (version > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    updated_by uuid not null references auth.users(id),
    unique (classroom_id, file_path)
);

create table if not exists public.classroom_folders (
    id uuid primary key default gen_random_uuid(),
    classroom_id uuid not null references public.classrooms(id) on delete cascade,
    folder_path text not null check (public.is_valid_workspace_path(folder_path)),
    created_at timestamptz not null default now(),
    unique (classroom_id, folder_path)
);

create or replace function public.is_provisioned_teacher()
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, pg_temp
as $$
    select exists (
        select 1 from public.classroom_teachers
        where user_id = auth.uid()
    );
$$;

create or replace function public.is_classroom_owner(p_classroom_id uuid)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, pg_temp
as $$
    select exists (
        select 1 from public.classrooms
        where id = p_classroom_id and teacher_id = auth.uid()
    );
$$;

create or replace function public.is_classroom_member(p_classroom_id uuid)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, pg_temp
as $$
    select exists (
        select 1 from public.classroom_members
        where classroom_id = p_classroom_id and user_id = auth.uid()
    );
$$;

create index if not exists classroom_members_user_idx
    on public.classroom_members(user_id, classroom_id);
create index if not exists classroom_members_presence_idx
    on public.classroom_members(classroom_id, last_seen_at desc);
create index if not exists classroom_files_room_path_idx
    on public.classroom_files(classroom_id, file_path);
create index if not exists classroom_folders_room_path_idx
    on public.classroom_folders(classroom_id, folder_path);

create or replace function public.protect_folder_classroom()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if new.classroom_id is distinct from old.classroom_id then
        raise exception 'A folder cannot be moved between classrooms';
    end if;
    return new;
end;
$$;

drop trigger if exists classroom_folders_room_immutable on public.classroom_folders;
create trigger classroom_folders_room_immutable
before update on public.classroom_folders
for each row execute function public.protect_folder_classroom();

create or replace function public.protect_classroom_owner()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if new.teacher_id is distinct from old.teacher_id then
        raise exception 'Classroom ownership cannot be changed';
    end if;
    return new;
end;
$$;

drop trigger if exists classrooms_owner_immutable on public.classrooms;
create trigger classrooms_owner_immutable
before update on public.classrooms
for each row execute function public.protect_classroom_owner();

create or replace function public.set_classroom_file_version()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if tg_op = 'INSERT' then
        new.version := 1;
        new.created_at := coalesce(new.created_at, now());
    else
        if new.classroom_id is distinct from old.classroom_id then
            raise exception 'A file cannot be moved between classrooms';
        end if;
        if new.content is distinct from old.content
           or new.file_path is distinct from old.file_path
           or new.language is distinct from old.language then
            new.version := old.version + 1;
            new.updated_at := now();
        else
            new.version := old.version;
            new.updated_at := old.updated_at;
        end if;
    end if;
    new.updated_by := auth.uid();
    if new.updated_by is null then
        raise exception 'An authenticated user is required to change classroom files';
    end if;
    return new;
end;
$$;

drop trigger if exists classroom_files_version on public.classroom_files;
create trigger classroom_files_version
before insert or update on public.classroom_files
for each row execute function public.set_classroom_file_version();

create or replace function public.save_classroom_file(
    p_classroom_id uuid,
    p_file_path text,
    p_content text,
    p_language text,
    p_expected_version bigint
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, pg_temp
as $$
declare
    saved_file public.classroom_files;
begin
    if auth.uid() is null or not public.is_classroom_owner(p_classroom_id) then
        raise exception 'Only the classroom owner may save shared files';
    end if;
    if not public.is_valid_workspace_path(p_file_path) then
        raise exception 'Invalid workspace-relative file path';
    end if;
    if p_content is null or p_language is null or p_expected_version < 0 then
        raise exception 'Invalid file content, language, or expected version';
    end if;

    if p_expected_version = 0 then
        insert into public.classroom_files(
            classroom_id, file_path, content, language, updated_by
        ) values (
            p_classroom_id, p_file_path, p_content, p_language, auth.uid()
        )
        on conflict (classroom_id, file_path) do nothing
        returning * into saved_file;
    else
        update public.classroom_files
        set content = p_content,
            language = p_language
        where classroom_id = p_classroom_id
          and file_path = p_file_path
          and version = p_expected_version
        returning * into saved_file;
    end if;
    if not found then
        raise exception 'File changed on the server or already exists; refresh before saving';
    end if;
    return to_jsonb(saved_file);
end;
$$;

create or replace function public.create_classroom(p_classroom_name text)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, pg_temp
as $$
declare
    new_classroom public.classrooms;
    generated_code text;
    random_bytes bytea;
    code_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    attempts integer := 0;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;
    if not public.is_provisioned_teacher() then
        raise exception 'Teacher access has not been provisioned for this account';
    end if;
    if p_classroom_name is null
       or length(btrim(p_classroom_name)) not between 1 and 100 then
        raise exception 'Classroom name must be between 1 and 100 characters';
    end if;

    loop
        attempts := attempts + 1;
        if attempts > 100 then
            raise exception 'Could not allocate a unique classroom code';
        end if;
        random_bytes := uuid_send(gen_random_uuid());
        select string_agg(
            substr(code_alphabet, (get_byte(random_bytes, positions.index) % 32) + 1, 1),
            ''
        ) into generated_code
        from generate_series(0, 4) as positions(index);
        begin
            insert into public.classrooms(classroom_name, join_code, teacher_id)
            values (btrim(p_classroom_name), generated_code, auth.uid())
            returning * into new_classroom;
            exit;
        exception when unique_violation then
            if not exists (
                select 1 from public.classrooms where join_code = generated_code
            ) then
                raise;
            end if;
        end;
    end loop;

    insert into public.classroom_folders(classroom_id, folder_path)
    values (new_classroom.id, 'src');
    insert into public.classroom_files(
        classroom_id, file_path, content, language, updated_by
    ) values (
        new_classroom.id,
        'src/Main.java',
        E'public class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello, classroom!");\n    }\n}\n',
        'java',
        auth.uid()
    );

    return jsonb_build_object(
        'id', new_classroom.id,
        'classroom_name', new_classroom.classroom_name,
        'join_code', new_classroom.join_code,
        'teacher_id', new_classroom.teacher_id,
        'is_active', new_classroom.is_active,
        'created_at', new_classroom.created_at
    );
end;
$$;

create or replace function public.join_classroom(
    p_join_code text,
    p_display_name text,
    p_registration_number text default null
)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog, public, pg_temp
as $$
declare
    target_id uuid;
begin
    if auth.uid() is null then
        raise exception 'Authentication is required';
    end if;
    if p_display_name is null
       or length(btrim(p_display_name)) not between 1 and 100 then
        raise exception 'Display name must be between 1 and 100 characters';
    end if;
    if p_registration_number is not null
       and length(p_registration_number) > 100 then
        raise exception 'Registration number must be 100 characters or fewer';
    end if;

    select id into target_id
    from public.classrooms
    where join_code = upper(btrim(p_join_code))
      and is_active = true;

    if target_id is null then
        raise exception 'Invalid room code or classroom is inactive';
    end if;

    insert into public.classroom_members(
        classroom_id, user_id, display_name, registration_number
    ) values (
        target_id, auth.uid(), btrim(p_display_name), nullif(btrim(p_registration_number), '')
    )
    on conflict (classroom_id, user_id) do update
        set display_name = excluded.display_name,
            registration_number = excluded.registration_number,
            last_seen_at = now();

    return target_id;
end;
$$;

create or replace function public.classroom_heartbeat(p_classroom_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public, pg_temp
as $$
begin
    update public.classroom_members
    set last_seen_at = now()
    where classroom_id = p_classroom_id and user_id = auth.uid();
    if not found then
        raise exception 'Classroom membership is required';
    end if;
end;
$$;

create or replace function public.rename_classroom_folder(
    p_classroom_id uuid,
    p_old_path text,
    p_new_path text
)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public, pg_temp
as $$
begin
    if auth.uid() is null or not exists (
        select 1 from public.classrooms
        where id = p_classroom_id and teacher_id = auth.uid()
    ) then
        raise exception 'Only the classroom owner may rename folders';
    end if;
    if not public.is_valid_workspace_path(p_old_path)
       or not public.is_valid_workspace_path(p_new_path) then
        raise exception 'Invalid workspace-relative folder path';
    end if;
    if p_old_path = p_new_path then
        return;
    end if;
    if not exists (
        select 1 from public.classroom_folders
        where classroom_id = p_classroom_id and folder_path = p_old_path
    ) then
        raise exception 'Folder does not exist';
    end if;
    if exists (
        select 1 from public.classroom_folders
        where classroom_id = p_classroom_id
          and folder_path = p_new_path
    ) or exists (
        select 1 from public.classroom_files
        where classroom_id = p_classroom_id
          and file_path = p_new_path
    ) then
        raise exception 'A file or folder already exists at the destination';
    end if;
    if left(p_new_path, length(p_old_path) + 1) = p_old_path || '/' then
        raise exception 'A folder cannot be moved inside itself';
    end if;
    if exists (
        select 1 from public.classroom_folders
        where classroom_id = p_classroom_id
          and left(folder_path, length(p_new_path) + 1) = p_new_path || '/'
    ) or exists (
        select 1 from public.classroom_files
        where classroom_id = p_classroom_id
          and left(file_path, length(p_new_path) + 1) = p_new_path || '/'
    ) then
        raise exception 'The destination folder is not empty';
    end if;

    update public.classroom_files
    set file_path = p_new_path || substr(file_path, length(p_old_path) + 1)
    where classroom_id = p_classroom_id
      and (file_path = p_old_path
           or left(file_path, length(p_old_path) + 1) = p_old_path || '/');
    update public.classroom_folders
    set folder_path = p_new_path || substr(folder_path, length(p_old_path) + 1)
    where classroom_id = p_classroom_id
      and (folder_path = p_old_path
           or left(folder_path, length(p_old_path) + 1) = p_old_path || '/');
end;
$$;

create or replace function public.delete_classroom_folder(
    p_classroom_id uuid,
    p_folder_path text,
    p_recursive boolean default false
)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public, pg_temp
as $$
begin
    if auth.uid() is null or not exists (
        select 1 from public.classrooms
        where id = p_classroom_id and teacher_id = auth.uid()
    ) then
        raise exception 'Only the classroom owner may delete folders';
    end if;
    if not public.is_valid_workspace_path(p_folder_path) then
        raise exception 'Invalid workspace-relative folder path';
    end if;
    if not exists (
        select 1 from public.classroom_folders
        where classroom_id = p_classroom_id and folder_path = p_folder_path
    ) then
        raise exception 'Folder does not exist';
    end if;
    if not p_recursive and (
        exists (
            select 1 from public.classroom_files
            where classroom_id = p_classroom_id
              and left(file_path, length(p_folder_path) + 1) = p_folder_path || '/'
        )
        or exists (
            select 1 from public.classroom_folders
            where classroom_id = p_classroom_id
              and left(folder_path, length(p_folder_path) + 1) = p_folder_path || '/'
        )
    ) then
        raise exception 'Folder is not empty';
    end if;

    delete from public.classroom_files
    where classroom_id = p_classroom_id
      and left(file_path, length(p_folder_path) + 1) = p_folder_path || '/';
    delete from public.classroom_folders
    where classroom_id = p_classroom_id
      and (folder_path = p_folder_path
           or left(folder_path, length(p_folder_path) + 1) = p_folder_path || '/');
end;
$$;

create or replace function public.active_classroom_member_count(p_classroom_id uuid)
returns integer
language plpgsql
stable
security definer
set search_path = pg_catalog, public, pg_temp
as $$
begin
    if not exists (
        select 1 from public.classrooms
        where id = p_classroom_id and teacher_id = auth.uid()
    ) then
        raise exception 'Only the classroom owner may view presence';
    end if;
    return (
        select count(*)::integer
        from public.classroom_members
        where classroom_id = p_classroom_id
          and last_seen_at > now() - interval '90 seconds'
    );
end;
$$;

alter table public.classrooms enable row level security;
alter table public.classroom_members enable row level security;
alter table public.classroom_files enable row level security;
alter table public.classroom_folders enable row level security;
alter table public.classroom_teachers enable row level security;

drop policy if exists teacher_accounts_read_self on public.classroom_teachers;
create policy teacher_accounts_read_self on public.classroom_teachers
for select to authenticated
using (user_id = (select auth.uid()));

drop policy if exists classrooms_read_participants on public.classrooms;
create policy classrooms_read_participants on public.classrooms
for select to authenticated
using (
    public.is_classroom_owner(id) or public.is_classroom_member(id)
);

drop policy if exists classrooms_update_owner on public.classrooms;
create policy classrooms_update_owner on public.classrooms
for update to authenticated
using (teacher_id = (select auth.uid()))
with check (teacher_id = (select auth.uid()));

drop policy if exists members_read_self_or_owner on public.classroom_members;
create policy members_read_self_or_owner on public.classroom_members
for select to authenticated
using (
    user_id = (select auth.uid())
    or public.is_classroom_owner(classroom_id)
);

drop policy if exists files_read_participants on public.classroom_files;
create policy files_read_participants on public.classroom_files
for select to authenticated
using (
    public.is_classroom_owner(classroom_id)
    or public.is_classroom_member(classroom_id)
);

drop policy if exists files_insert_owner on public.classroom_files;
create policy files_insert_owner on public.classroom_files
for insert to authenticated
with check (
    updated_by = (select auth.uid())
    and public.is_classroom_owner(classroom_id)
);

drop policy if exists files_update_owner on public.classroom_files;
create policy files_update_owner on public.classroom_files
for update to authenticated
using (
    public.is_classroom_owner(classroom_id)
)
with check (
    updated_by = (select auth.uid())
    and public.is_classroom_owner(classroom_id)
);

drop policy if exists files_delete_owner on public.classroom_files;
create policy files_delete_owner on public.classroom_files
for delete to authenticated
using (
    public.is_classroom_owner(classroom_id)
);

drop policy if exists folders_read_participants on public.classroom_folders;
create policy folders_read_participants on public.classroom_folders
for select to authenticated
using (
    public.is_classroom_owner(classroom_id)
    or public.is_classroom_member(classroom_id)
);

drop policy if exists folders_insert_owner on public.classroom_folders;
create policy folders_insert_owner on public.classroom_folders
for insert to authenticated
with check (
    public.is_classroom_owner(classroom_id)
);

drop policy if exists folders_update_owner on public.classroom_folders;
create policy folders_update_owner on public.classroom_folders
for update to authenticated
using (
    public.is_classroom_owner(classroom_id)
)
with check (
    public.is_classroom_owner(classroom_id)
);

drop policy if exists folders_delete_owner on public.classroom_folders;
create policy folders_delete_owner on public.classroom_folders
for delete to authenticated
using (
    public.is_classroom_owner(classroom_id)
);

grant usage on schema public to authenticated;
revoke all on public.classroom_teachers from anon, authenticated;
revoke all on public.classrooms from anon, authenticated;
revoke all on public.classroom_members from anon, authenticated;
revoke all on public.classroom_files from anon, authenticated;
revoke all on public.classroom_folders from anon, authenticated;
grant select on public.classroom_teachers to authenticated;
grant execute on function public.is_provisioned_teacher() to authenticated;
revoke all on function public.is_provisioned_teacher() from public, anon;
revoke all on function public.is_classroom_owner(uuid) from public, anon;
revoke all on function public.is_classroom_member(uuid) from public, anon;
grant execute on function public.is_classroom_owner(uuid) to authenticated;
grant execute on function public.is_classroom_member(uuid) to authenticated;
grant select, update on public.classrooms to authenticated;
grant select on public.classroom_members to authenticated;
grant select, insert, update, delete on public.classroom_files to authenticated;
grant select, insert, update, delete on public.classroom_folders to authenticated;
revoke all on function public.create_classroom(text) from public, anon;
revoke all on function public.save_classroom_file(uuid, text, text, text, bigint) from public, anon;
revoke all on function public.join_classroom(text, text, text) from public, anon;
revoke all on function public.classroom_heartbeat(uuid) from public, anon;
revoke all on function public.rename_classroom_folder(uuid, text, text) from public, anon;
revoke all on function public.delete_classroom_folder(uuid, text, boolean) from public, anon;
revoke all on function public.active_classroom_member_count(uuid) from public, anon;
grant execute on function public.create_classroom(text) to authenticated;
grant execute on function public.save_classroom_file(uuid, text, text, text, bigint) to authenticated;
grant execute on function public.join_classroom(text, text, text) to authenticated;
grant execute on function public.classroom_heartbeat(uuid) to authenticated;
grant execute on function public.rename_classroom_folder(uuid, text, text) to authenticated;
grant execute on function public.delete_classroom_folder(uuid, text, boolean) to authenticated;
grant execute on function public.active_classroom_member_count(uuid) to authenticated;

alter table public.classroom_files replica identity full;
alter table public.classroom_folders replica identity full;
do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'classroom_files'
    ) then
        alter publication supabase_realtime add table public.classroom_files;
    end if;
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'classroom_folders'
    ) then
        alter publication supabase_realtime add table public.classroom_folders;
    end if;
end;
$$;
