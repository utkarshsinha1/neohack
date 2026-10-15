package com.classroom.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspacePathTest {
    @Test
    void acceptsNormalizedRelativePathsAndDetectsLanguages() {
        assertEquals("src/Main.java", WorkspacePath.normalize("src/Main.java"));
        assertEquals("java", WorkspacePath.languageFor("src/Main.java"));
        assertEquals("text", WorkspacePath.languageFor("README.md"));
    }

    @Test
    void rejectsPathsThatCanEscapeOrAliasTheWorkspace() {
        for (String path : new String[] {
                "", "/etc/passwd", "../secret.txt", "src/../secret.txt",
                "src//Main.java", "./Main.java", "src\\Main.java", "src/.."}) {
            assertThrows(IllegalArgumentException.class, () -> WorkspacePath.normalize(path), path);
        }
    }

    @Test
    void workspaceFileRequiresAValidPathAndPositiveVersion() {
        UUID classroomId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceFile(
            fileId, classroomId, "../Main.java", "", "java", 1, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceFile(
            fileId, classroomId, "Main.java", "", "java", 0, Instant.now()));
    }
}
