package com.classroom.live;

import java.io.IOException;
import java.net.URI;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record SupabaseConfig(String projectUrl, String publishableKey) {
    public SupabaseConfig {
        if (projectUrl == null || projectUrl.isBlank()
                || publishableKey == null || publishableKey.isBlank()) {
            throw new IllegalArgumentException("Supabase URL and publishable key are required");
        }
        projectUrl = projectUrl.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(projectUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Supabase project URL is invalid", exception);
        }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean local = "http".equalsIgnoreCase(uri.getScheme())
            && ("localhost".equalsIgnoreCase(uri.getHost())
                || "127.0.0.1".equals(uri.getHost()));
        if ((!secure && !local) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null
                    && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException(
                "Supabase project URL must use HTTPS (HTTP is allowed only on localhost)"
            );
        }
        publishableKey = publishableKey.trim();
        if (publishableKey.startsWith("sb_secret_")
                || publishableKey.toLowerCase().contains("service_role")
                || jwtHasServiceRole(publishableKey)) {
            throw new IllegalArgumentException(
                "A service-role key must never be used by the desktop application"
            );
        }
    }

    private static boolean jwtHasServiceRole(String key) {
        String[] segments = key.split("\\.");
        if (segments.length != 3) {
            return false;
        }
        try {
            byte[] payload = java.util.Base64.getUrlDecoder().decode(segments[1]);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(payload).path("role").asText().equals("service_role");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static SupabaseConfig load() throws IOException {
        String environmentUrl = System.getenv("FERIC_SUPABASE_URL");
        String environmentKey = System.getenv("FERIC_SUPABASE_PUBLISHABLE_KEY");
        Path path = Path.of(System.getProperty("user.home"), ".feric", "supabase.properties");
        Properties userProperties = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                userProperties.load(input);
            }
        }

        Properties bundledProperties = new Properties();
        try (InputStream input = SupabaseConfig.class.getResourceAsStream(
                "/supabase.properties")) {
            if (input != null) {
                bundledProperties.load(input);
            }
        }
        return load(environmentUrl, environmentKey, userProperties, bundledProperties);
    }

    static SupabaseConfig load(
            String environmentUrl,
            String environmentKey,
            Properties userProperties,
            Properties bundledProperties) {
        String url = firstNonBlank(
            environmentUrl,
            userProperties.getProperty("projectUrl"),
            bundledProperties.getProperty("projectUrl")
        );
        String key = firstNonBlank(
            environmentKey,
            userProperties.getProperty("publishableKey"),
            bundledProperties.getProperty("publishableKey")
        );
        if (url == null || url.isBlank() || key == null || key.isBlank()) {
            throw new IllegalStateException(
                "Configure FERIC_SUPABASE_URL and FERIC_SUPABASE_PUBLISHABLE_KEY, "
                    + "or ~/.feric/supabase.properties"
            );
        }
        return new SupabaseConfig(url, key);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
