package io.github.weslocke.outrider

/**
 * Colours and frame for the app's own screens (settings, sign-in, "no link", the Ask overlay). The page picks the
 * theme and tells the app through `OutriderApp.setTheme(name)`; names the app doesn't know fall back to LCARS. The
 * names and colours match the page's themes (static/themes/<name>.css in Outrider).
 */
data class AppTheme(
    val name: String,
    val background: Int,
    /** Header bar, primary buttons. */
    val primary: Int,
    /** Secondary buttons, the header's end cap. */
    val secondary: Int,
    /** Third accent: the side block, input underline. */
    val accent: Int,
    /** Body text. */
    val text: Int,
    /** Text on primary/secondary/accent fills. */
    val onFill: Int,
    /** Errors only. */
    val alert: Int,
    /** Button corners: LCARS pills, square for chamfered Elite panels, slightly rounded consoles. */
    val cornerRadiusDp: Float,
    val frame: Frame,
    /** Text on secondary buttons: the same as [onFill] unless the secondary fill is dark (the modern theme). */
    val onSecondary: Int = onFill,
    /** Card surface for the [Frame.CARD] frame. */
    val surface: Int = background,
) {
    /** How a screen's frame is drawn. */
    enum class Frame {
        /** LCARS: a top bar joined to a side bar by a rounded elbow, an end cap and a side block. */
        ELBOW,
        /** Elite's HUD: a thin outline with cut corners and a short filled header tab. */
        CHAMFER,
        /** Babylon 5's consoles: a thin bordered panel with an angled header tab. */
        CONSOLE,
        /** A modern dark interface: no decoration, the screen on one rounded card. */
        CARD,
    }

    companion object {
        val LCARS = AppTheme(
            name = "lcars",
            background = 0xFF000000.toInt(),
            primary = 0xFFFF9933.toInt(),
            secondary = 0xFFCC99CC.toInt(),
            accent = 0xFF9999FF.toInt(),
            text = 0xFFF4E9DA.toInt(),   // lcars.css --text (peach is for headings there)
            onFill = 0xFF000000.toInt(),
            alert = 0xFFFF6666.toInt(),  // lcars.css --bad
            cornerRadiusDp = 28f,
            frame = Frame.ELBOW,
        )

        val ELITE = AppTheme(
            name = "elite",
            background = 0xFF000000.toInt(),
            primary = 0xFFFF7A00.toInt(),
            secondary = 0xFFB85500.toInt(),
            accent = 0xFF3FC8F4.toInt(),
            text = 0xFFFFA040.toInt(),
            onFill = 0xFF000000.toInt(),
            alert = 0xFFFF3B30.toInt(),
            cornerRadiusDp = 0f,
            frame = Frame.CHAMFER,
        )

        val BABYLON5 = AppTheme(
            name = "babylon5",
            background = 0xFF050B14.toInt(),
            primary = 0xFF4A7FB5.toInt(),
            secondary = 0xFF8FA3B8.toInt(),
            accent = 0xFF6FD3FF.toInt(),
            text = 0xFFD8E6F2.toInt(),
            onFill = 0xFF050B14.toInt(),
            alert = 0xFFFFB020.toInt(),
            cornerRadiusDp = 4f,
            frame = Frame.CONSOLE,
        )

        /** Babylon 5's Narn Regime: rust red and ochre on dark red-brown, angular. */
        val NARN = AppTheme(
            name = "narn",
            background = 0xFF120806.toInt(),
            primary = 0xFFB83A1E.toInt(),
            secondary = 0xFF7A2A18.toInt(),
            accent = 0xFFE0A040.toInt(),
            text = 0xFFE8C9A0.toInt(),
            // parchment on the rust fills: dark text on #7A2A18 was 2.0:1
            onFill = 0xFFF2D9B8.toInt(),
            alert = 0xFFFFC24B.toInt(),
            cornerRadiusDp = 2f,
            frame = Frame.CHAMFER,
        )

        /** Babylon 5's Minbari Federation: lilac and pearl on deep indigo, sea-glass accents, soft pointed-window cards. */
        val MINBARI = AppTheme(
            name = "minbari",
            background = 0xFF0F0C1C.toInt(),
            primary = 0xFFB7A3E6.toInt(),
            secondary = 0xFF5B4F86.toInt(),
            accent = 0xFF7FD1C4.toInt(),
            text = 0xFFECE7F6.toInt(),
            onFill = 0xFF0F0C1C.toInt(),
            alert = 0xFFFF8FA3.toInt(),
            cornerRadiusDp = 14f,
            frame = Frame.CARD,
            // pearl on the violet secondary buttons (6.0:1); indigo on lilac and sea-glass
            onSecondary = 0xFFECE7F6.toInt(),
            surface = 0xFF1A1630.toInt(),
        )
        /** Babylon 5's Centauri Republic: gold on royal purple and plum-black, cream text, notched consoles. */
        val CENTAURI = AppTheme(
            name = "centauri",
            background = 0xFF12080F.toInt(),
            primary = 0xFFD4AF37.toInt(),
            secondary = 0xFF5E2A5A.toInt(),
            // gold, not the page's crimson (#A3203A), which is a fill only there: 2.65:1 as a line on this background
            accent = 0xFFD4AF37.toInt(),
            text = 0xFFF3E6C8.toInt(),
            onFill = 0xFF12080F.toInt(),
            alert = 0xFFFF6F7F.toInt(),
            cornerRadiusDp = 0f,
            frame = Frame.CONSOLE,
            onSecondary = 0xFFF3E6C8.toInt(),
        )
        /** Star Wars, Imperial / Sith: crimson on black, steel-white text, hard edges. */
        val SITH = AppTheme(
            name = "sith",
            background = 0xFF000000.toInt(),
            primary = 0xFFD0021B.toInt(),
            secondary = 0xFF5A0A12.toInt(),
            accent = 0xFFE8E8E8.toInt(),
            text = 0xFFC9CED6.toInt(),
            // white on the reds: black on #5A0A12 was 1.5:1
            onFill = 0xFFFFFFFF.toInt(),
            alert = 0xFFFFB000.toInt(),
            cornerRadiusDp = 0f,
            frame = Frame.CHAMFER,
        )

        /** Star Wars, Rebel Alliance: cockpit orange and sand on blue-black, blue for active. */
        val ALLIANCE = AppTheme(
            name = "alliance",
            background = 0xFF0B0F14.toInt(),
            primary = 0xFFF28C28.toInt(),
            secondary = 0xFFC9B98F.toInt(),
            accent = 0xFF4FA3D9.toInt(),
            text = 0xFFF2EBDD.toInt(),
            onFill = 0xFF0B0F14.toInt(),
            alert = 0xFFE8453C.toInt(),
            cornerRadiusDp = 6f,
            frame = Frame.CONSOLE,
        )

        /** A modern dark mode: slate greys (not pure black), one blue accent, rounded cards. */
        val DARK = AppTheme(
            name = "dark",
            background = 0xFF121417.toInt(),
            primary = 0xFF4F8CFF.toInt(),
            secondary = 0xFF2A2F37.toInt(),
            accent = 0xFF4F8CFF.toInt(),
            text = 0xFFE6E8EB.toInt(),
            onFill = 0xFFFFFFFF.toInt(),
            alert = 0xFFFF6B6B.toInt(),
            cornerRadiusDp = 10f,
            frame = Frame.CARD,
            onSecondary = 0xFFE6E8EB.toInt(),
            surface = 0xFF1C1F24.toInt(),
        )

        private val ALL = listOf(LCARS, ELITE, BABYLON5, NARN, MINBARI, CENTAURI, SITH, ALLIANCE, DARK).associateBy { it.name }

        fun named(name: String?): AppTheme = ALL[name?.lowercase()] ?: LCARS

        val all: Collection<AppTheme> get() = ALL.values

        /** Field fill (accent over the background) and hint (text over that fill) opacities, used by Ui.kt. */
        const val FIELD_FILL_ALPHA = 0x26
        const val HINT_ALPHA = 0xA0   // 0x70 left Elite's hint at 2.6:1
    }
}

/** WCAG 2 contrast, for the theme checks in the unit tests. Pure. */
object Contrast {
    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(argb: Int) =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    fun ratio(fg: Int, bg: Int): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** [fg] at [alpha] (0-255) over an opaque [bg]. */
    fun over(fg: Int, alpha: Int, bg: Int): Int {
        val a = alpha / 255.0
        fun mix(shift: Int) = (((fg shr shift) and 0xFF) * a + ((bg shr shift) and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
