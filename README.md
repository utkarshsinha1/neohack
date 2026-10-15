# Feric — Live Coding Classroom

Feric is a Java Swing classroom IDE. Authenticated teachers create persistent,
room-based projects stored in Supabase; authenticated students join by room code
and receive a read-only shared project view. Students can copy shared files or
the project into a separate local workspace.

## Requirements

- JDK 17 or newer
- Maven 3.8 or newer
- For Java document formatting, `google-java-format` available as a
  `google-java-format` command on `PATH` (optional)

RSyntaxTextArea is declared in `pom.xml` and downloaded by Maven.

## Build and run

Run the role chooser from the project directory:

```sh
mvn clean test
mvn exec:java
```

Before launching, configure Supabase as described below. The launcher presents
a Teacher or Student role choice and supports Supabase email/password sign-in
and registration. A teacher account must be explicitly provisioned by the
project administrator before it can create classrooms.

On macOS, install the optional formatter with Homebrew:

```sh
brew install google-java-format
```

On Windows, install a `google-java-format` CLI distribution and place its
launcher on `PATH`. Without it, document saving still works and the status bar
explains that formatting was skipped. The Format action and `Cmd/Ctrl+Shift+I`
report formatter availability; Java is formatted only by google-java-format,
not by indentation heuristics.

## Build distributable apps

Application sources use the `com.classroom.live` package. Maven builds a
self-contained executable JAR:

```sh
mvn clean package
java -jar target/teacher-classroom-1.0.0.jar
```

To create an app image that includes a Java runtime, build it on the operating
system and CPU architecture where it will run:

- macOS: `sh scripts/package.sh` creates `dist/Feric.app` and
  `dist/Feric-macOS.zip`.
- Linux: `sh scripts/package.sh` creates `dist/Feric/` and
  `dist/Feric-Linux.zip`.
- Windows PowerShell: `.\scripts\package.ps1` creates `dist\Feric\`
  and `dist\Feric-Windows.zip`.

The packaged application icon and Swing window icons use the supplied Feric
wordmark in `logos/feric-wordmark.png`. Regenerate the cross-platform icon
files on macOS with `swift scripts/generate-feric-icons.swift "$PWD"`.

The app image includes the Java runtime for launching this application, so
recipients do not need to install Java just to open it. The built-in code
runner needs language toolchains installed on the recipient machine (a JDK
including `javac` for Java, a C/C++ compiler for C/C++, and the corresponding
interpreter for Python or JavaScript). `jpackage` produces platform-specific
outputs and must be run on the matching OS; `.github/workflows/package.yml`
builds and uploads Windows x64, Linux x64, and macOS ARM64 and Intel Feric app
images using GitHub Actions.

The packaged runtime explicitly includes `java.desktop` (Swing/AWT),
`java.net.http` (Supabase HTTP and WebSocket connections), and `jdk.compiler`
(the Java code runner). On macOS, the packaging script also
verifies the clean app bundle before archiving it, avoiding Finder metadata
that can be attached when copying into a synced workspace folder. Locally
built macOS bundles are not Developer ID signed or notarized; signing and
notarization are required before distributing the app to other Macs without
Gatekeeper warnings.

## Supabase setup

1. Create a Supabase project and open **SQL Editor**.
2. Run the SQL files in `supabase/migrations/` in filename order. The first
   migration creates the classroom, membership, file, folder, and
   teacher-authorization tables; constraints, triggers, indexes, RPC
   operations, RLS policies, grants, and Realtime publication entries. The
   second migration changes room codes to five characters and rotates any
   existing longer codes.
   For a project where migration 001 was already applied, run migration 002
   once in Supabase SQL Editor and restart Feric. Existing classroom codes
   change during that upgrade; share each room's new five-character code.
3. In **Authentication → Providers**, enable Email/password sign-in. If email
   confirmation is enabled, users must confirm their address before signing
   in. Keep confirmation enabled for a public deployment.
4. Register a teacher account from Feric or Supabase Auth. A project
   administrator must then authorize that account to create rooms by running
   this statement in the Supabase SQL Editor (substitute the teacher email):

   ```sql
   insert into public.classroom_teachers (user_id)
   select id from auth.users where email = 'teacher@example.com'
   on conflict (user_id) do nothing;
   ```

   Only an administrator using the SQL Editor should provision teachers.
   Authenticated clients have read-only access to their own provisioning row
   and cannot grant themselves teacher status. Students need only register
   and sign in; their authenticated email identifies their membership, with no
   separate name or registration-number prompt.
5. Configure the project URL and **publishable** key. The publishable key (or
   legacy `anon` key) is intended for client applications; never use a
   `service_role` or secret key in Feric.

For a shell-launched development build:

```sh
export FERIC_SUPABASE_URL="https://YOUR_PROJECT_REF.supabase.co"
export FERIC_SUPABASE_PUBLISHABLE_KEY="YOUR_PUBLISHABLE_KEY"
mvn exec:java
```

For a packaged app launched from Finder or Explorer, create
`~/.feric/supabase.properties` (on Windows, `%USERPROFILE%\.feric\supabase.properties`):

```properties
projectUrl=https://YOUR_PROJECT_REF.supabase.co
publishableKey=YOUR_PUBLISHABLE_KEY
```

The project URL and publishable key are client configuration, not privileged
credentials. Passwords and access/refresh tokens are held in memory only; the
application does not save passwords or a service-role key.

### Authorization and synchronization model

- `auth.uid()` is the identity boundary. Room codes and display names are not
  authentication.
- `create_classroom` atomically creates a room, its `src` folder, and a starter
  `src/Main.java`. It assigns a collision-checked, random five-character code.
- `join_classroom` verifies an active code and records the authenticated user
  as a member. The student UI accepts five-character codes and legacy
  ten-character codes until migration 002 has been applied. The unique
  `(classroom_id, user_id)` key makes rejoining idempotent.
- RLS permits room members to read that room's files and folders. Only the
  owning teacher can write, rename, or delete shared files and folders. Owner
  identity cannot be updated. Teacher authorization is provisioned separately
  by the project administrator.
- File versions increment on content, path, or language changes. Autosave
  submits the expected version, so a stale update is rejected rather than
  silently replacing a newer server version.
- Realtime Postgres Changes is subscribed per classroom. The client uses the
  documented Supabase Realtime WebSocket protocol, refreshes the project
  snapshot after reconnect, and applies file/folder events to the Swing EDT.
- Student personal files live under `~/.feric/workspaces/<classroom-id>` and
  are never sent back to the shared classroom tables. In the student IDE, the
  teacher's read-only live code and the student's editable local copy are
  shown side by side. Selecting a shared file opens both panes immediately;
  the student's existing local file is preserved.

Supabase does not provide the database as a public source-code directory.
Feric uses authenticated PostgREST and RPC requests; PostgreSQL RLS remains the
authorization layer even if a client bypasses the UI. The migration enables
Realtime for the two workspace tables. Verify in **Database → Publications**
that `classroom_files` and `classroom_folders` are listed in
`supabase_realtime` after applying the migration.

### Run two clients

Run `mvn exec:java` twice on the same computer, sign in with the teacher account
in one and a student account in the other, then create a room and join it with
the displayed room code. New rooms use five-character codes; existing ten-
character rooms remain joinable until migration 002 is applied. Students join
using their authenticated account; no name or registration number is requested.
No LAN address or inbound firewall rule is required. To test on separate
computers, configure the same Supabase project on both and use two separate
authenticated accounts.

## Editor shortcuts

- `Cmd/Ctrl+S`: Save
- `Cmd/Ctrl+C`, `Cmd/Ctrl+V`, `Cmd/Ctrl+X`, `Cmd/Ctrl+A`: Copy, paste,
  cut, and select all in code editors and room-code fields. Right-click also
  opens these text actions.
- `Save now`: immediately persist the active teacher file; normal edits are
  saved automatically after a short debounce.
- `Format Java`: format with `google-java-format` when installed.

The Java runner compiles the available Java workspace sources in a temporary
directory. Temporary compilation output is not uploaded. Program execution is
not sandboxed; only run code you trust.
