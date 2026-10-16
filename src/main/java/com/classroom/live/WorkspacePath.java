package com.classroom.live;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class WorkspacePath {
    private WorkspacePath() {}

    public static String normalize(String raw) {
        Objects.requireNonNull(raw, "Workspace path is required");
        if (raw.isBlank() || raw.length() > 1024 || raw.indexOf('\\') >= 0
                || raw.startsWith("/") || raw.endsWith("/")
                || raw.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid workspace-relative path");
        }
        String[] parts = raw.split("/", -1);
        for (String part : parts) {
            if (part.isBlank() || part.equals(".") || part.equals("..")
                    || part.length() > 255) {
                throw new IllegalArgumentException("Invalid workspace-relative path");
            }
        }
        try {
            Path normalized = Path.of(raw).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..")
                    || !normalized.toString().replace('\\', '/').equals(raw)) {
                throw new IllegalArgumentException("Invalid workspace-relative path");
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Invalid workspace-relative path", exception);
        }
        return raw;
    }

    public static String languageFor(String path) {
        String normalized = normalize(path);
        int extensionIndex = normalized.lastIndexOf('.');
        if (extensionIndex < 0) {
            return "text";
        }
        return switch (normalized.substring(extensionIndex + 1).toLowerCase(Locale.ROOT)) {
            case "java" -> "java";
            case "py" -> "python";
            case "js", "mjs", "jsx" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "c", "h" -> "c";
            case "cc", "cpp", "cxx", "hpp" -> "cpp";
            case "cs" -> "csharp";
            case "html", "htm" -> "html";
            case "css" -> "css";
            case "json" -> "json";
            case "xml" -> "xml";
            case "sql" -> "sql";
            case "md", "txt", "properties", "sh" -> "text";
            default -> "text";
        };
    }
}
