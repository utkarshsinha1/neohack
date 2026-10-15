package com.classroom.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ClassroomService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SupabaseClient client;

    public ClassroomService(SupabaseClient client) {
        this.client = client;
    }

    public Classroom create(String name) throws IOException {
        ObjectNode request = JSON.createObjectNode().put("p_classroom_name", name);
        JsonNode result = client.rpc("create_classroom", request);
        return classroom(result);
    }

    public List<Classroom> listOwned() throws IOException {
        JsonNode result = client.get(
            "classrooms?select=id,classroom_name,join_code,is_active,created_at"
                + "&teacher_id=eq." + client.userId()
                + "&order=created_at.desc"
        );
        List<Classroom> rooms = new ArrayList<>();
        for (JsonNode node : result) {
            rooms.add(classroom(node));
        }
        return List.copyOf(rooms);
    }

    public Classroom get(UUID classroomId) throws IOException {
        JsonNode result = client.get(
            "classrooms?id=eq." + classroomId + "&select=id,classroom_name,join_code,is_active"
        );
        if (!result.isArray() || result.isEmpty()) {
            throw new IOException("Classroom is no longer available to this account");
        }
        return classroom(result.get(0));
    }

    public UUID join(String joinCode) throws IOException {
        String email = client.userEmail();
        int atIndex = email.indexOf('@');
        String displayName = atIndex > 0 ? email.substring(0, atIndex) : email;
        ObjectNode request = JSON.createObjectNode()
            .put("p_join_code", joinCode)
            .put("p_display_name", displayName)
            .putNull("p_registration_number");
        JsonNode result = client.rpc("join_classroom", request);
        try {
            return UUID.fromString(result.asText());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Supabase returned an invalid classroom identifier", exception);
        }
    }

    public Snapshot load(UUID classroomId) throws IOException {
        JsonNode filesResult = client.get(
            "classroom_files?classroom_id=eq." + classroomId
                + "&select=id,classroom_id,file_path,content,language,version,updated_at"
                + "&order=file_path.asc"
        );
        JsonNode foldersResult = client.get(
            "classroom_folders?classroom_id=eq." + classroomId
                + "&select=folder_path&order=folder_path.asc"
        );
        List<WorkspaceFile> files = new ArrayList<>();
        for (JsonNode node : filesResult) {
            files.add(file(node));
        }
        List<String> folders = new ArrayList<>();
        for (JsonNode node : foldersResult) {
            folders.add(WorkspacePath.normalize(node.path("folder_path").asText()));
        }
        return new Snapshot(files, folders);
    }

    public WorkspaceFile save(
            UUID classroomId, String path, String content, String language) throws IOException {
        return save(classroomId, path, content, language, 0);
    }

    public WorkspaceFile save(
            UUID classroomId,
            String path,
            String content,
            String language,
            long expectedVersion) throws IOException {
        String normalized = WorkspacePath.normalize(path);
        ObjectNode row = JSON.createObjectNode()
            .put("p_classroom_id", classroomId.toString())
            .put("p_file_path", normalized)
            .put("p_content", content)
            .put("p_language", language == null
                ? WorkspacePath.languageFor(normalized) : language)
            .put("p_expected_version", expectedVersion);
        return file(client.rpc("save_classroom_file", row));
    }

    public void renameFile(UUID classroomId, String oldPath, String newPath) throws IOException {
        String source = WorkspacePath.normalize(oldPath);
        String destination = WorkspacePath.normalize(newPath);
        ObjectNode patch = JSON.createObjectNode()
            .put("file_path", destination)
            .put("language", WorkspacePath.languageFor(destination));
        JsonNode updated = client.patch(
            "classroom_files?classroom_id=eq." + classroomId
                + "&file_path=eq." + client.encodeQuery(source),
            patch,
            "return=representation"
        );
        if (!updated.isArray() || updated.isEmpty()) {
            throw new IOException("The file was not found or you are not its classroom owner");
        }
    }

    public void deleteFile(UUID classroomId, String path) throws IOException {
        String normalized = WorkspacePath.normalize(path);
        client.delete("classroom_files?classroom_id=eq." + classroomId
            + "&file_path=eq." + client.encodeQuery(normalized));
    }

    public void createFolder(UUID classroomId, String folderPath) throws IOException {
        String normalized = WorkspacePath.normalize(folderPath);
        String[] parts = normalized.split("/");
        ArrayNode rows = JSON.createArrayNode();
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (!current.isEmpty()) {
                current.append('/');
            }
            current.append(part);
            rows.add(JSON.createObjectNode()
                .put("classroom_id", classroomId.toString())
                .put("folder_path", current.toString()));
        }
        client.post(
            "classroom_folders?on_conflict=classroom_id%2Cfolder_path",
            rows,
            "resolution=ignore-duplicates,return=minimal"
        );
    }

    public void renameFolder(UUID classroomId, String oldPath, String newPath)
            throws IOException {
        ObjectNode request = JSON.createObjectNode()
            .put("p_classroom_id", classroomId.toString())
            .put("p_old_path", WorkspacePath.normalize(oldPath))
            .put("p_new_path", WorkspacePath.normalize(newPath));
        client.rpc("rename_classroom_folder", request);
    }

    public void deleteFolder(UUID classroomId, String path, boolean recursive)
            throws IOException {
        ObjectNode request = JSON.createObjectNode()
            .put("p_classroom_id", classroomId.toString())
            .put("p_folder_path", WorkspacePath.normalize(path))
            .put("p_recursive", recursive);
        client.rpc("delete_classroom_folder", request);
    }

    public void heartbeat(UUID classroomId) throws IOException {
        client.rpc(
            "classroom_heartbeat",
            JSON.createObjectNode().put("p_classroom_id", classroomId.toString())
        );
    }

    public int activeMemberCount(UUID classroomId) throws IOException {
        return client.rpc(
            "active_classroom_member_count",
            JSON.createObjectNode().put("p_classroom_id", classroomId.toString())
        ).asInt();
    }

    private static Classroom classroom(JsonNode node) throws IOException {
        try {
            return new Classroom(
                UUID.fromString(node.path("id").asText()),
                node.path("classroom_name").asText(),
                node.path("join_code").asText(),
                node.path("is_active").asBoolean(true)
            );
        } catch (RuntimeException exception) {
            throw new IOException("Supabase returned invalid classroom data", exception);
        }
    }

    private static WorkspaceFile file(JsonNode node) throws IOException {
        try {
            return new WorkspaceFile(
                UUID.fromString(node.path("id").asText()),
                UUID.fromString(node.path("classroom_id").asText()),
                node.path("file_path").asText(),
                node.path("content").asText(),
                node.path("language").asText(),
                node.path("version").asLong(),
                Instant.parse(node.path("updated_at").asText())
            );
        } catch (RuntimeException exception) {
            throw new IOException("Supabase returned invalid classroom file data", exception);
        }
    }

    public record Classroom(UUID id, String name, String joinCode, boolean active) {}

    public record Snapshot(List<WorkspaceFile> files, List<String> folders) {
        public Snapshot {
            files = files.stream()
                .sorted(Comparator.comparing(WorkspaceFile::path))
                .toList();
            folders = folders.stream().sorted().toList();
        }
    }
}
