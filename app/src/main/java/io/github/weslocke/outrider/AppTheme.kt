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

        private val ALL = listOf(LCARS, ELITE, BABYLON5).associateBy { it.name }

        fun named(name: String?): AppTheme = ALL[name?.lowercase()] ?: LCARS
    }
}
