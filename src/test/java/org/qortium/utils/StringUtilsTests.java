package org.qortium.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StringUtilsTests {

    @Test
    public void testSanitizeStringRemovesInvalidFilenameCharacters() {
        assertEquals("bad_name.txt", StringUtils.sanitizeString(" bad<>:\"/\\|?* name.txt "));
    }

    @Test
    public void testSanitizeStringCollapsesWhitespaceWithoutRegex() {
        assertEquals("multi_word_name", StringUtils.sanitizeString("\tmulti \r\n word   name\n"));
    }
}
