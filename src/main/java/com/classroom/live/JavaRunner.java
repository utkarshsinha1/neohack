
package com.classroom.live;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class JavaRunner {

    private JavaRunner() {}

    public static String run(
            String source, String language,
            String className, String input) {

        if (source == null || source.isBlank()) {
            return "Please enter some code first.";
        }

        String lang = language == null ? "" :
            language.trim().toLowerCase(Locale.ROOT);

        try {
            return switch (lang) {
                case "java" -> runJava(source, className, input);
                case "c" -> runNative(source, input, "c");
                case "c++", "cpp" -> runNative(source, input, "cpp");
                case "c#" , "csharp" -> runCSharp(source, input);
                case "python" -> runScript(source, input, "python");
                case "javascript", "js" ->
                    runScript(source, input, "javascript");
                default -> "Unsupported language: " + language;
            };
        } catch (Exception e) {
            return "Runner error: " + e.getMessage();
        }
    }

    public static String runJavaProject(
            java.util.Map<String, String> sourceFiles,
            String mainClass,
            String input) {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            return "The project contains no source files.";
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory("feric-java-project-");
            List<Path> javaSources = new ArrayList<>();
            for (var entry : sourceFiles.entrySet()) {
                String relative = WorkspacePath.normalize(entry.getKey());
                if (!relative.toLowerCase(Locale.ROOT).endsWith(".java")) {
                    continue;
                }
                Path destination = directory.resolve(relative).normalize();
                if (!destination.startsWith(directory)) {
                    return "Invalid Java source path: " + relative;
                }
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, entry.getValue(), StandardCharsets.UTF_8);
                javaSources.add(destination);
            }
            if (javaSources.isEmpty()) {
                return "The project contains no Java source files.";
            }
            javaSources.sort(Comparator.comparing(Path::toString));
            String log = directory.resolve("compile.txt").toString();
            List<String> compile = new ArrayList<>(List.of(
                javaTool("javac"), "-encoding", "UTF-8", "-d", directory.toString()));
            javaSources.forEach(path -> compile.add(path.toString()));
            Process compiler = new ProcessBuilder(compile)
                .redirectErrorStream(true)
                .redirectOutput(Path.of(log).toFile())
                .start();
            if (!compiler.waitFor(30, TimeUnit.SECONDS)) {
                compiler.destroyForcibly();
                compiler.waitFor();
                return "Java project compilation timed out.\n" + read(Path.of(log));
            }
            String compileMessages = read(Path.of(log));
            if (compiler.exitValue() != 0) {
                return "=== JAVA PROJECT COMPILATION FAILED ===\n\n" + compileMessages;
            }

            String selectedClass = mainClass == null ? "" : mainClass.trim();
            if (!selectedClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
                return "Invalid Java main class name. Example: Main";
            }
            if (!selectedClass.contains(".")) {
                String classFileName = selectedClass + ".java";
                String selectedSource = sourceFiles.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith(classFileName))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst().orElse("");
                Matcher packageMatcher = Pattern.compile(
                    "(?m)^\\s*package\\s+"
                        + "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;"
                ).matcher(selectedSource);
                if (packageMatcher.find()) {
                    selectedClass = packageMatcher.group(1) + "." + selectedClass;
                }
            }
            Path output = directory.resolve("output.txt");
            Process program = new ProcessBuilder(
                    javaTool("java"), "-cp", directory.toString(), selectedClass)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
            writeInput(program, input);
            if (!program.waitFor(10, TimeUnit.SECONDS)) {
                program.destroyForcibly();
                program.waitFor();
                return "Java project compiled successfully, but execution timed out.\n\n"
                    + read(output);
            }
            return result("JAVA PROJECT", compileMessages, read(output), program.exitValue());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Java project runner error: " + exception.getMessage();
        } finally {
            deleteDirectory(directory);
        }
    }

    // ---------------- JAVA ----------------

    private static String runJava(
            String source, String className, String input)
            throws Exception {

        if (className == null ||
            !className.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {//regex exp !!!
            return "Invalid Java class name. Example: Main";
        }

        Path dir = Files.createTempDirectory("classroom-java-");

        try {
            Path sourceFile = dir.resolve(className + ".java");
            Path compileLog = dir.resolve("compile.txt");
            Path outputLog = dir.resolve("output.txt");

            Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

            Process compiler = new ProcessBuilder(
                javaTool("javac"),
                "-encoding", "UTF-8",
                "-d", dir.toString(),
                sourceFile.toString()
            )
                .redirectErrorStream(true)
                .redirectOutput(compileLog.toFile())
                .start();

            if (!compiler.waitFor(20, TimeUnit.SECONDS)) {
                compiler.destroyForcibly();
                compiler.waitFor();
                return "Java compilation timed out.\n" + read(compileLog);
            }

            String compileMessages = read(compileLog);

            if (compiler.exitValue() != 0) {
                return "=== JAVA COMPILATION FAILED ===\n\n"
                    + compileMessages;
            }

            String packageName = "";
            Matcher matcher = Pattern.compile(
                "(?m)^\\s*package\\s+" +
                "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;"
            ).matcher(source);

            if (matcher.find()) {
                packageName = matcher.group(1) + ".";
            }

            Process program = new ProcessBuilder(
                javaTool("java"),
                "-cp", dir.toString(),
                packageName + className
            )
                .redirectErrorStream(true)
                .redirectOutput(outputLog.toFile())
                .start();

            writeInput(program, input);

            if (!program.waitFor(10, TimeUnit.SECONDS)) {
                program.destroyForcibly();
                program.waitFor();
                return "Java compiled successfully, but execution timed out.\n\n"
                    + read(outputLog);
            }

            return result("JAVA", compileMessages, read(outputLog),
                          program.exitValue());

        } finally {
            deleteDirectory(dir);
        }
    }

    private static String javaTool(String name) {
        boolean windows = System.getProperty("os.name")
            .toLowerCase(Locale.ROOT).contains("win");
        String executable = windows ? name + ".exe" : name;
        Path path = Path.of(System.getProperty("java.home"), "bin", executable);
        return Files.isExecutable(path) ? path.toString() : executable;
    }

    // ---------------- C AND C++ ----------------

    private static String runNative(
            String source, String input, String language)
            throws Exception {

        Path dir = Files.createTempDirectory("classroom-native-");

        try {
            boolean cpp = language.equals("cpp");

            Path sourceFile = dir.resolve(cpp ? "main.cpp" : "main.c");
            boolean windows = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT).contains("win");
            Path executable = dir.resolve(windows ? "program.exe" : "program");
            Path compileLog = dir.resolve("compile.txt");
            Path outputLog = dir.resolve("output.txt");

            Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

            String compiler = cpp
                ? findCompiler("clang++", "g++")
                : findCompiler("clang", "gcc");

            if (compiler == null) {
                return (cpp ? "C++" : "C") +
                    " compiler not found.\n\n" +
                    "Install a C/C++ compiler and add it to PATH.";
            }

            List<String> command = new ArrayList<>();
            command.add(compiler);

            if (cpp) {
                command.add("-std=c++17");
            } else {
                command.add("-std=c11");
            }

            command.add("-Wall");
            command.add("-Wextra");
            command.add("-O0");
            command.add(sourceFile.toString());
            command.add("-o");
            command.add(executable.toString());

            Process compilerProcess = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(compileLog.toFile())
                .start();

            if (!compilerProcess.waitFor(30, TimeUnit.SECONDS)) {
                compilerProcess.destroyForcibly();
                compilerProcess.waitFor();
                return "Compilation timed out.\n" + read(compileLog);
            }

            String compileMessages = read(compileLog);

            if (compilerProcess.exitValue() != 0) {
                return "=== " + (cpp ? "C++" : "C")
                    + " COMPILATION FAILED ===\n\n" + compileMessages;
            }

            Process program = new ProcessBuilder(executable.toString())
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputLog.toFile())
                .start();

            writeInput(program, input);

            if (!program.waitFor(10, TimeUnit.SECONDS)) {
                program.destroyForcibly();
                program.waitFor();
                return "Compiled successfully, but execution timed out.\n\n"
                    + read(outputLog);
            }

            return result(cpp ? "C++" : "C", compileMessages,
                          read(outputLog), program.exitValue());

        } finally {
            deleteDirectory(dir);
        }
    }

    private static String findCompiler(String... names) {
        for (String name : names) {
            try {
                Process process = new ProcessBuilder(name, "--version")
                    .redirectErrorStream(true)
                    .start();

                if (process.waitFor(3, TimeUnit.SECONDS)) {
                    if (process.exitValue() == 0) return name;
                } else {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    // ---------------- C# ----------------

    private static String runCSharp(
            String source, String input) throws Exception {

        Path dir = Files.createTempDirectory("classroom-csharp-");

        try {
            String version = commandOutput("dotnet", "--version");

            if (version == null || version.isBlank()) {
                return "C# requires the .NET SDK.\n" +
                    "Install it from https://dotnet.microsoft.com/download";
            }

            Matcher versionMatcher =
                Pattern.compile("^(\\d+)\\.").matcher(version.trim());

            if (!versionMatcher.find()) {
                return "Could not detect the installed .NET SDK version: "
                    + version;
            }

            int major = Integer.parseInt(versionMatcher.group(1));

            Path project = dir.resolve("Classroom.csproj");
            Path sourceFile = dir.resolve("Program.cs");
            Path buildLog = dir.resolve("build.txt");
            Path outputLog = dir.resolve("output.txt");

            String framework = "net" + major + ".0";

            String projectXml =
                "<Project Sdk=\"Microsoft.NET.Sdk\">" +
                "<PropertyGroup>" +
                "<OutputType>Exe</OutputType>" +
                "<TargetFramework>" + framework + "</TargetFramework>" +
                "<ImplicitUsings>enable</ImplicitUsings>" +
                "<Nullable>enable</Nullable>" +
                "</PropertyGroup></Project>";

            Files.writeString(project, projectXml, StandardCharsets.UTF_8);
            Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

            Process build = new ProcessBuilder(
                "dotnet", "build", project.toString(),
                "--configuration", "Release", "--nologo"
            )
                .redirectErrorStream(true)
                .redirectOutput(buildLog.toFile())
                .start();

            if (!build.waitFor(60, TimeUnit.SECONDS)) {
                build.destroyForcibly();
                build.waitFor();
                return "C# build timed out.\n" + read(buildLog);
            }

            String buildMessages = read(buildLog);

            if (build.exitValue() != 0) {
                return "=== C# COMPILATION FAILED ===\n\n" + buildMessages;
            }

            Path dll = dir.resolve(
                "bin/Release/" + framework + "/Classroom.dll"
            );

            if (!Files.exists(dll)) {
                return "C# build completed, but the output DLL was not found.\n"
                    + buildMessages;
            }

            Process program = new ProcessBuilder(
                "dotnet", dll.toString()
            )
                .redirectErrorStream(true)
                .redirectOutput(outputLog.toFile())
                .start();

            writeInput(program, input);

            if (!program.waitFor(10, TimeUnit.SECONDS)) {
                program.destroyForcibly();
                program.waitFor();
                return "C# compiled successfully, but execution timed out.\n\n"
                    + read(outputLog);
            }

            return result("C#", buildMessages, read(outputLog),
                          program.exitValue());

        } finally {
            deleteDirectory(dir);
        }
    }

    private static String commandOutput(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(),
                                    StandardCharsets.UTF_8);
            }

            if (!process.waitFor(5, TimeUnit.SECONDS) ||
                process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }

            return output.trim();

        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- PYTHON AND JAVASCRIPT ----------------

    private static String runScript(
            String source, String input, String language)
            throws Exception {

        Path dir = Files.createTempDirectory("classroom-script-");

        try {
            boolean python = language.equals("python");

            Path sourceFile = dir.resolve(
                python ? "main.py" : "main.js"
            );
            Path outputLog = dir.resolve("output.txt");

            Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

            String command = python
                ? (System.getProperty("os.name").toLowerCase(Locale.ROOT)
                    .contains("win") ? "python" : "python3")
                : "node";

            Process program = new ProcessBuilder(
                command, sourceFile.toString()
            )
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputLog.toFile())
                .start();

            writeInput(program, input);

            if (!program.waitFor(10, TimeUnit.SECONDS)) {
                program.destroyForcibly();
                program.waitFor();
                return language + " execution timed out.\n\n"
                    + read(outputLog);
            }

            return result(python ? "PYTHON" : "JAVASCRIPT",
                          "", read(outputLog), program.exitValue());

        } catch (IOException e) {
            String tool = language.equals("python") ? "Python 3" : "Node.js";
            return tool + " was not found or could not start.\n" +
                "Install it and ensure the command is on PATH.\n\n" +
                e.getMessage();

        } finally {
            deleteDirectory(dir);
        }
    }

    // ---------------- SHARED HELPERS ----------------

    private static void writeInput(Process process, String input)
            throws IOException {

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(
                (input == null ? "" : input).getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    private static String result(
            String language, String buildMessages,
            String output, int exitCode) {

        StringBuilder result = new StringBuilder();

        result.append("=== ")
              .append(language)
              .append(" BUILD SUCCESSFUL ===\n");

        if (buildMessages != null && !buildMessages.isBlank()) {
            result.append("\nBuild messages:\n").append(buildMessages);
        }

        result.append("\n=== PROGRAM OUTPUT ===\n")
              .append(output.isBlank() ? "(No output)\n" : output)
              .append("\nExit code: ")
              .append(exitCode);

        return result.toString();
    }

    private static String read(Path path) throws IOException {
        return Files.exists(path)
            ? Files.readString(path, StandardCharsets.UTF_8)
            : "";
    }

    private static void deleteDirectory(Path dir) {
        if (dir == null) return;

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                 .forEach(path -> {
                     try {
                         Files.deleteIfExists(path);
                     } catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {}
    }
}
