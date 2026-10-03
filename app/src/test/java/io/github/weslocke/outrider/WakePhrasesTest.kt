package io.github.weslocke.outrider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class WakePhrasesTest {
    private val bpe = File("voice/assets/kws/bpe.model").inputStream().use { Bpe.load(it) }
    private val tokens = File("voice/assets/kws/tokens.txt").readLines().map { it.substringBefore(' ') }.toSet()

    @Test fun phrasesForTheWord() {
        val lines = WakePhrases.keywords(bpe, " vespa ", WakePhrases.Sensitivity.NORMAL).split("/")
        assertEquals(listOf(
            "▁O K ▁ VE S P A :1.5 #0.2 @OK_VESPA",
            "▁OKAY ▁ VE S P A :1.5 #0.2 @OKAY_VESPA",
            "▁HE Y ▁ VE S P A :1.5 #0.2 @HEY_VESPA",
            "▁HE LL O ▁ VE S P A :1.5 #0.2 @HELLO_VESPA",
        ), lines)
    }

    @Test fun twoWordNamesKeepOneTag() {
        assertEquals("@HEY_MISS_IVANOVA", WakePhrases.keywords(bpe, "Miss Ivanova", WakePhrases.Sensitivity.HIGH).split("/")[2].substringAfterLast(' '))
    }

    @Test fun wordsThatCantBeWakeWords() {
        assertNull(WakePhrases.problem(bpe, tokens, "Vespa"))
        assertNull(WakePhrases.problem(bpe, tokens, "Garibaldi"))
        assertNotNull(WakePhrases.problem(bpe, tokens, ""))
        assertNotNull(WakePhrases.problem(bpe, tokens, "123"))
        assertNotNull(WakePhrases.problem(bpe, tokens, "a".repeat(30)))
    }

    @Test fun sensitivityCycles() {
        assertEquals(WakePhrases.Sensitivity.HIGH, WakePhrases.Sensitivity.NORMAL.next())
        assertEquals(WakePhrases.Sensitivity.LOW, WakePhrases.Sensitivity.HIGH.next())
    }
}
