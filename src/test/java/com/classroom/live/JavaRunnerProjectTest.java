package com.classroom.live;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaRunnerProjectTest {
    @Test
    void compilesAndRunsMultipleJavaFilesTogether() {
        Map<String, String> files = Map.of(
            "src/Main.java",
            "public class Main { public static void main(String[] args) { "
                + "System.out.println(Helper.message()); } }",
            "src/Helper.java",
            "final class Helper { static String message() { return \"workspace-ok\"; } }"
        );

        String output = JavaRunner.runJavaProject(files, "Main", "");

        assertTrue(output.contains("JAVA PROJECT BUILD SUCCESSFUL"), output);
        assertTrue(output.contains("workspace-ok"), output);
    }
}
