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
) {
    /** How a screen's frame is drawn. */
    enum class Frame {
        /** LCARS: a top bar joined to a side bar by a rounded elbow, an end cap and a side block. */
        ELBOW,
        /** Elite's HUD: a thin outline with cut corners and a short filled header tab. */
        CHAMFER,
        /** Babylon 5's consoles: a thin bordered panel with an angled header tab. */
        CONSOLE,
    }

    companion object {
        val LCARS = AppTheme(
            name = "lcars",
            background = 0xFF000000.toInt(),
            primary = 0xFFFF9933.toInt(),
            secondary = 0xFFCC99CC.toInt(),
            accent = 0xFF9999FF.toInt(),
            text = 0xFFFFCC99.toInt(),
            onFill = 0xFF000000.toInt(),
            alert = 0xFFFF5555.toInt(),
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
            onFill = 0xFF120806.toInt(),
            alert = 0xFFFFC24B.toInt(),
            cornerRadiusDp = 2f,
            frame = Frame.CHAMFER,
        )

        /** Star Wars, Imperial / Sith: crimson on black, steel-white text, hard edges. */
        val SITH = AppTheme(
            name = "sith",
            background = 0xFF000000.toInt(),
            primary = 0xFFD0021B.toInt(),
            secondary = 0xFF5A0A12.toInt(),
            accent = 0xFFE8E8E8.toInt(),
            text = 0xFFC9CED6.toInt(),
            onFill = 0xFF000000.toInt(),
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

        private val ALL = listOf(LCARS, ELITE, BABYLON5, NARN, SITH, ALLIANCE).associateBy { it.name }

        fun named(name: String?): AppTheme = ALL[name?.lowercase()] ?: LCARS
    }
}
