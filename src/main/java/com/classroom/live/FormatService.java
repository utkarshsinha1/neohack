package com.classroom.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class FormatService {
    public static final class FormatResult {
        private final String formatted;
        private final String message;

        private FormatResult(String formatted, String message) {
            this.formatted = formatted;
            this.message = message;
        }

        public boolean succeeded() {
            return formatted != null;
        }

        public String getFormatted() {
            return formatted;
        }

        public String getMessage() {
            return message;
        }
    }

    private FormatService() {}

    public static FormatResult formatJava(String source) {
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("classroom-format-");
            Path input = directory.resolve("Source.java");
            Path output = directory.resolve("formatted.java");
            Path errors = directory.resolve("formatter-errors.txt");
            Files.writeString(input, source, StandardCharsets.UTF_8);
            process = new ProcessBuilder("google-java-format", input.toString())
                .redirectOutput(output.toFile())
                .redirectError(errors.toFile())
                .start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                return new FormatResult(null, "Java formatting timed out.");
            }
            if (process.exitValue() != 0) {
                String details = Files.exists(errors)
                    ? Files.readString(errors, StandardCharsets.UTF_8).trim()
                    : "";
                return new FormatResult(
                    null,
                    details.isEmpty()
                        ? "google-java-format failed (exit code " + process.exitValue() + ")."
                        : "google-java-format failed: " + details
                );
            }
            return new FormatResult(
                Files.readString(output, StandardCharsets.UTF_8),
                "Formatted with google-java-format."
            );
        } catch (IOException e) {
            return new FormatResult(
                null,
                "Java formatter unavailable. Install google-java-format to enable Java formatting."
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FormatResult(null, "Java formatting was interrupted.");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    paths.sorted((left, right) -> right.compareTo(left))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                                // Temporary files are best-effort cleanup.
                            }
                        });
                } catch (IOException ignored) {
                    // Temporary files are best-effort cleanup.
                }
            }
        }
    }
}
