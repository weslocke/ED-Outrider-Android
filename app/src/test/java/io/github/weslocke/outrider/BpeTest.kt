package io.github.weslocke.outrider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BpeTest {
    // the model's own bpe.model, fetched by ./gradlew fetchVoice (preBuild runs it before the tests)
    private val bpe = File("voice/assets/kws/bpe.model").inputStream().use { Bpe.load(it) }
    private val tokens = File("voice/assets/kws/tokens.txt").readLines().map { it.substringBefore(' ') }.toSet()

    @Test fun matchesSentencepiece() {
        // bpe_gold.json: sentencepiece's own split of each phrase with this bpe.model
        val gold = JSONObject(javaClass.getResource("/bpe_gold.json")!!.readText())
        for (phrase in gold.keys()) {
            assertEquals(phrase, gold.getString(phrase), bpe.encode(phrase).joinToString(" "))
        }
    }

    @Test fun cleansWhatPeopleType() {
        assertEquals("HEY ECLAIR", Bpe.clean("hey  éclair!"))
        assertEquals("HEY R D", Bpe.clean("Hey R2D2"))   // no digits in the model
        assertEquals(bpe.encode("HEY VESPA"), bpe.encode(" hey, Vespa. "))
        assertEquals(emptyList<String>(), bpe.encode("?!"))
    }

    @Test fun everyPieceIsKnown() {
        for (w in listOf("OK VESPA", "HEY GARIBALDI", "HELLO X Y Z", "HEY R2D2")) assertTrue(w, bpe.known(bpe.encode(w), tokens))
    }
}
