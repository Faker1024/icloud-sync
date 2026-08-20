package com.faker1024.icloudsync.core.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipSafetyValidatorTest {
    @Test
    fun acceptsNestedRelativePaths() {
        assertTrue(ZipSafetyValidator.validateEntryName("2026/08/IMG_0001.HEIC"))
        assertTrue(ZipSafetyValidator.validateEntryName("a/../b/photo.jpg"))
    }

    @Test
    fun rejectsPathsThatEscapeOrAreAbsolute() {
        assertFalse(ZipSafetyValidator.validateEntryName("../photo.jpg"))
        assertFalse(ZipSafetyValidator.validateEntryName("folder/../../photo.jpg"))
        assertFalse(ZipSafetyValidator.validateEntryName("..\\photo.jpg"))
        assertFalse(ZipSafetyValidator.validateEntryName("/private/photo.jpg"))
        assertFalse(ZipSafetyValidator.validateEntryName("C:\\Users\\photo.jpg"))
    }

    @Test
    fun ignoresMacMetadataEntries() {
        assertTrue(ZipSafetyValidator.shouldIgnore("__MACOSX/._IMG_0001.JPG"))
        assertTrue(ZipSafetyValidator.shouldIgnore("photos/.DS_Store"))
        assertTrue(ZipSafetyValidator.shouldIgnore("photos/._IMG_0001.JPG"))
        assertFalse(ZipSafetyValidator.shouldIgnore("photos/IMG_0001.JPG"))
    }
}
