package com.privateinvest.crm.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FormatsTest {

    @Test
    void acnCheckDigit() {
        assertTrue(Formats.isValidAcn("000 000 019"));
        assertTrue(Formats.isValidAcn("612045182"));
        assertFalse(Formats.isValidAcn("612045183"));
        assertFalse(Formats.isValidAcn("12345"));
    }

    @Test
    void abnModulus89() {
        assertTrue(Formats.isValidAbn("51 824 753 556"));
        assertTrue(Formats.isValidAbn("31612045182"));
        assertFalse(Formats.isValidAbn("51 824 753 557"));
        assertFalse(Formats.isValidAbn("123"));
    }

    @Test
    void emailAndCleaning() {
        assertTrue(Formats.isValidEmail("olivia@riversidelogistics.example"));
        assertFalse(Formats.isValidEmail("olivia@"));
        assertEquals("Clean", Formats.clean("  Clean  "));
        assertEquals(null, Formats.clean("   "));
        assertEquals(null, Formats.clean(null));
    }
}
