package com.classroom.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
}
