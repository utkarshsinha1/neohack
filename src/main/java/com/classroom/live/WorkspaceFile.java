package com.classroom.live;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkspaceFile(
        UUID id,
        UUID classroomId,
        String path,
        String content,
        String language,
        long version,
        Instant updatedAt) {
    public WorkspaceFile {
        Objects.requireNonNull(id, "File id is required");
        Objects.requireNonNull(classroomId, "Classroom id is required");
        path = WorkspacePath.normalize(path);
        Objects.requireNonNull(content, "File content is required");
        Objects.requireNonNull(language, "File language is required");
        Objects.requireNonNull(updatedAt, "Updated timestamp is required");
        if (version < 1) {
            throw new IllegalArgumentException("File version must be positive");
        }
    }
}
