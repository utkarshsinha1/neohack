package com.classroom.live;

import java.util.Locale;
import java.util.regex.Pattern;

public final class RoomCode {
    private static final Pattern VALID_CODE =
        Pattern.compile("(?:[A-HJ-NP-Z2-9]{5}|[A-HJ-NP-Z2-9]{10})");

    private RoomCode() {}

    public static String normalize(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Enter the classroom code");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!VALID_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "Room code must be 5 characters, or 10 for a room created before migration 002");
        }
        return normalized;
    }

    public static boolean isLegacy(String code) {
        return code != null && code.length() == 10;
    }
}
