package io.github.weslocke.outrider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    @Test fun errorsInWords() {
        assertEquals("Didn't catch that", Speech.describeError(7))   // no match
        assertEquals("Didn't catch that", Speech.describeError(6))   // speech timeout
        assertTrue(Speech.describeError(9).contains("microphone"))
        assertTrue(Speech.describeError(99).contains("99"))
    }

    @Test fun onlyLanguageAndServiceErrorsRetryOnline() {
        assertTrue(Speech.retryOnline(13))
        assertTrue(Speech.retryOnline(12))
        assertFalse(Speech.retryOnline(7))
        assertFalse(Speech.retryOnline(9))
    }

    @Test fun answerLine() {
        assertEquals("Fuel 80 percent.", Speech.answerLine(AskAnswer("Fuel 80 percent.", true, "fixed", "fuel")))
        assertEquals("Outrider has no answer for that", Speech.answerLine(AskAnswer("", false, "none", null)))
    }
}
