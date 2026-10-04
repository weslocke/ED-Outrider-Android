package io.github.weslocke.outrider

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioReadsTest {
    @Test fun readResults() {
        assertEquals(AudioReads.Action.SAMPLES, AudioReads.action(1600))
        assertEquals(AudioReads.Action.WAIT, AudioReads.action(0))
        // the audio server restarted: open a new recorder
        assertEquals(AudioReads.Action.REOPEN, AudioReads.action(-6))
        // every other error would spin forever if retried
        for (n in listOf(-1, -2, -3)) assertEquals(AudioReads.Action.FAIL, AudioReads.action(n))
    }

    @Test fun microphonePermission() {
        assertEquals(Speech.Mic.GRANTED, Speech.micState(granted = true, askedBefore = true, rationale = false))
        assertEquals(Speech.Mic.ASKABLE, Speech.micState(granted = false, askedBefore = false, rationale = false))
        assertEquals(Speech.Mic.ASKABLE, Speech.micState(granted = false, askedBefore = true, rationale = true))
        assertEquals(Speech.Mic.BLOCKED, Speech.micState(granted = false, askedBefore = true, rationale = false))
    }
}
