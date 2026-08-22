package com.nuvio.tv.core.debrid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasynewsTitleMatcherTest {
    @Test
    fun `title matcher accepts punctuation used inside release names`() {
        val matcher = easynewsTitleRegex("Go! Go! Cory Carson")

        assertTrue(matcher.containsMatchIn("Go!.Go!.Cory.Carson.S01E02.1080p.NF.WEB-DL.mkv"))
        assertTrue(matcher.containsMatchIn("Go.Go.Cory-Carson.S01E02.mkv"))
        assertFalse(matcher.containsMatchIn("Go.Dog.Go.S01E02.mkv"))
    }
}
