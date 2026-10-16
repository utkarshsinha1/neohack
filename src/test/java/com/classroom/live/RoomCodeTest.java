package com.classroom.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoomCodeTest {
    @Test
    void acceptsFiveCharacterCodesAndNormalizesCaseAndWhitespace() {
        assertEquals("AB234", RoomCode.normalize(" ab234 "));
    }

    @Test
    void acceptsLegacyTenCharacterCodesUntilTheyAreMigrated() {
        assertEquals("ABCDEFGHJK", RoomCode.normalize("abcdefghjk"));
        assertTrue(RoomCode.isLegacy("ABCDEFGHJK"));
        assertFalse(RoomCode.isLegacy("AB234"));
    }

    @Test
    void rejectsCodesThatAreNotFiveValidCharacters() {
        for (String code : new String[] {"", "ABCD", "ABCDEF", "AB01I", "AB C4",
                "ABCDEFGHIJK"}) {
            assertThrows(IllegalArgumentException.class, () -> RoomCode.normalize(code), code);
        }
    }
}
