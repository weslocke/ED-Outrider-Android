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

    private fun reply(answer: String, spoken: Boolean, command: String?, speakHere: Boolean = true, lastSaid: String? = null) =
        Speech.tabletReply(AskAnswer(answer, spoken, "fixed", command), speakHere, lastSaid)

    @Test fun anAnswerOutriderSpokeIsOnlyShown() {
        assertEquals(Speech.TabletReply("Fuel 80 percent.", null), reply("Fuel 80 percent.", true, "fuel"))
    }

    @Test fun anUnspokenAnswerIsReadOnTheTabletWhenAllowed() {
        assertEquals(Speech.TabletReply("Fuel 80 percent.", "Fuel 80 percent."), reply("Fuel 80 percent.", false, "fuel"))
        assertEquals(Speech.TabletReply("Fuel 80 percent.", null), reply("Fuel 80 percent.", false, "fuel", speakHere = false))
    }

    @Test fun againRepeatsTheTabletsOwnLastAnswer() {
        val refusal = "No window is speaking aloud just now, so there is nothing to say again."
        // no PC window: Outrider refuses, the tablet says its own last answer again
        assertEquals(Speech.TabletReply("Fuel 80 percent.", "Fuel 80 percent."),
            reply(refusal, false, "again", lastSaid = "Fuel 80 percent."))
        // shown again but not read when the tablet doesn't read answers
        assertEquals(Speech.TabletReply("Fuel 80 percent.", null),
            reply(refusal, false, "again", speakHere = false, lastSaid = "Fuel 80 percent."))
        // nothing said here yet: Outrider's refusal, as any answer
        assertEquals(Speech.TabletReply(refusal, refusal), reply(refusal, false, "again"))
        // a PC window repeated it aloud: only shown
        assertEquals(Speech.TabletReply("Fuel 80 percent.", null), reply("Fuel 80 percent.", true, "again", lastSaid = "Older."))
    }

    @Test fun showingAPageIsNeverReadAloud() {
        assertEquals(Speech.TabletReply("The Map.", null), reply("The Map.", false, "show_page"))
    }

    @Test fun theNewSpokenAnswersAreReadLikeAnyOther() {
        for (command in listOf("where_am_i", "bookmark_here", "bookmark_remove")) {
            assertEquals(command, Speech.TabletReply("Done here.", "Done here."), reply("Done here.", false, command))
        }
    }
}
