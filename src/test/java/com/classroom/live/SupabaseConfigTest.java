package com.classroom.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SupabaseConfigTest {
    @Test
    void allowsHttpsAndLocalDevelopmentUrls() {
        assertEquals(
            "https://project.supabase.co",
            new SupabaseConfig("https://project.supabase.co/", "sb_publishable_test").projectUrl());
        assertEquals(
            "http://localhost:54321",
            new SupabaseConfig("http://localhost:54321", "test-key").projectUrl());
    }

    @Test
    void rejectsInsecureRemoteUrlsAndPrivilegedKeys() {
        assertThrows(IllegalArgumentException.class,
            () -> new SupabaseConfig("http://example.com", "test-key"));
        assertThrows(IllegalArgumentException.class,
            () -> new SupabaseConfig("https://project.supabase.co/path", "test-key"));
        assertThrows(IllegalArgumentException.class,
            () -> new SupabaseConfig("https://project.supabase.co", "sb_secret_test"));

        String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"role\":\"service_role\"}".getBytes(StandardCharsets.UTF_8));
        String jwt = "header." + payload + ".signature";
        assertThrows(IllegalArgumentException.class,
            () -> new SupabaseConfig("https://project.supabase.co", jwt));
    }

    @Test
    void loadsBundledDefaultsAndAllowsUserOverrides() {
        Properties bundled = new Properties();
        bundled.setProperty("projectUrl", "https://bundled.supabase.co");
        bundled.setProperty("publishableKey", "bundled-key");

        SupabaseConfig defaults = SupabaseConfig.load(
            null, null, new Properties(), bundled);
        assertEquals("https://bundled.supabase.co", defaults.projectUrl());
        assertEquals("bundled-key", defaults.publishableKey());

        Properties user = new Properties();
        user.setProperty("projectUrl", "https://user.supabase.co");
        SupabaseConfig overridden = SupabaseConfig.load(
            null, null, user, bundled);
        assertEquals("https://user.supabase.co", overridden.projectUrl());
        assertEquals("bundled-key", overridden.publishableKey());

        SupabaseConfig environment = SupabaseConfig.load(
            "https://environment.supabase.co", "environment-key", user, bundled);
        assertEquals("https://environment.supabase.co", environment.projectUrl());
        assertEquals("environment-key", environment.publishableKey());
    }

    @Test
    void packagesTheSupabaseDefaults() throws Exception {
        Properties bundled = new Properties();
        try (InputStream input = SupabaseConfig.class.getResourceAsStream(
                "/supabase.properties")) {
            assertNotNull(input);
            bundled.load(input);
        }

        SupabaseConfig config = SupabaseConfig.load(
            null, null, new Properties(), bundled);
        assertEquals("https://wehjzngyixppkbqerrwa.supabase.co", config.projectUrl());
        assertEquals(
            "sb_publishable_MJlP5zdXkjfI1DgqHJ4Mfw_JS2VO4NO",
            config.publishableKey()
        );
    }
}
