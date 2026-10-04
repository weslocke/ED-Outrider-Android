package io.github.weslocke.outrider

import org.junit.Assert.assertTrue
import org.junit.Test

/** Every theme's app screens stay readable (WCAG: 4.5:1 for text, 3:1 for large bold labels and large hints). */
class ContrastTest {
    private fun check(what: String, ratio: Double, min: Double) = assertTrue("$what: %.2f < $min".format(ratio), ratio >= min)

    @Test fun everyThemeIsReadable() {
        for (t in AppTheme.all) {
            val surface = if (t.frame == AppTheme.Frame.CARD) t.surface else t.background
            check("${t.name} text", Contrast.ratio(t.text, surface), 4.5)
            check("${t.name} alert", Contrast.ratio(t.alert, surface), 4.5)
            // button labels are 22 sp bold: large text
            check("${t.name} primary button", Contrast.ratio(t.onFill, t.primary), 3.0)
            check("${t.name} secondary button", Contrast.ratio(t.onSecondary, t.secondary), 3.0)
            val fill = Contrast.over(t.accent, AppTheme.FIELD_FILL_ALPHA, surface)
            check("${t.name} field hint", Contrast.ratio(Contrast.over(t.text, AppTheme.HINT_ALPHA, fill), fill), 3.0)
        }
    }

    @Test fun ratioMatchesKnownValues() {
        assertTrue(Math.abs(Contrast.ratio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()) - 21.0) < 0.01)
        assertTrue(Math.abs(Contrast.ratio(0xFF777777.toInt(), 0xFFFFFFFF.toInt()) - 4.48) < 0.01)
    }
}
