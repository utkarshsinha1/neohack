begin;

alter table public.classrooms
    drop constraint if exists classrooms_join_code_check;

do $$
declare
    classroom_row record;
    generated_code text;
    random_bytes bytea;
    code_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    attempts integer;
begin
    for classroom_row in
        select id
        from public.classrooms
        where length(join_code) <> 5
    loop
        attempts := 0;
        loop
            attempts := attempts + 1;
            if attempts > 100 then
                raise exception 'Could not allocate a unique five-character classroom code';
            end if;
            random_bytes := uuid_send(gen_random_uuid());
            select string_agg(
                substr(
                    code_alphabet,
                    (get_byte(random_bytes, positions.index) % 32) + 1,
                    1
                ),
                ''
            ) into generated_code
            from generate_series(0, 4) as positions(index);

            exit when not exists (
                select 1
                from public.classrooms
                where join_code = generated_code
            );
        end loop;

        update public.classrooms
        set join_code = generated_code
        where id = classroom_row.id;
    end loop;
end;
$$;

alter table public.classrooms
    add constraint classrooms_join_code_check
    check (join_code ~ '^[A-HJ-NP-Z2-9]{5}$');

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

notify pgrst, 'reload schema';
commit;
