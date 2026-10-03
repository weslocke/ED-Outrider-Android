package io.github.weslocke.outrider

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * The app's own screens (settings, sign-in, "no link", update): a frame drawn like the tablet page's shell, a
 * title, a body, at most one text field and a row of buttons. Built in code so the colours follow [AppTheme].
 */
class Screen(private val context: Context, private val theme: AppTheme, spec: Spec) {

    data class Button(val label: String, val primary: Boolean = true, val onClick: () -> Unit)

    data class Field(
        val hint: String,
        val text: String = "",
        val password: Boolean = false,
        /** Called by the keyboard's action key. */
        val onDone: () -> Unit,
    )

    data class Spec(
        val title: String,
        val body: CharSequence? = null,
        val field: Field? = null,
        val error: String? = null,
        val buttons: List<Button> = emptyList(),
        /** Small print at the bottom: versions, the address. */
        val footer: String? = null,
    )

    private val res = context.resources
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, res.displayMetrics)

    private val condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    val view: View
    val field: EditText?
    private val bodyView: TextView
    private val errorView: TextView

    init {
        val frame = FrameLayout(context).apply { background = FrameDrawable(theme, dp(1f)) }
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inset = FrameDrawable.contentInsets(theme.frame)
            setPadding(inset[0].dp(), inset[1].dp(), inset[2].dp(), inset[3].dp())
        }
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        column.addView(text(spec.title.uppercase(), 40f, theme.primary, condensedBold))
        bodyView = text(spec.body ?: "", 22f, theme.text, condensed).apply {
            setPadding(0, 12.dp(), 0, 0)
            setLineSpacing(0f, 1.15f)
            visibility = if (spec.body == null) View.GONE else View.VISIBLE
        }
        column.addView(bodyView)

        field = spec.field?.let { f -> editText(context, f).also { column.addView(it, fieldParams()) } }

        errorView = text(spec.error ?: "", 22f, theme.alert, condensedBold).apply {
            setPadding(0, 16.dp(), 0, 0)
            visibility = if (spec.error == null) View.GONE else View.VISIBLE
        }
        column.addView(errorView)

        if (spec.buttons.isNotEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 32.dp(), 0, 0)
            }
            spec.buttons.forEachIndexed { i, b ->
                row.addView(button(context, b), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 64.dp(),
                ).apply { if (i > 0) leftMargin = 16.dp() })
            }
            column.addView(row)
        }

        spec.footer?.let {
            column.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            column.addView(text(it, 16f, ColorUtils.setAlphaComponent(theme.text, 0xA0), condensed).apply {
                setPadding(0, 32.dp(), 0, 0)
            })
        }
        view = frame
    }

    fun setBody(text: CharSequence) {
        bodyView.text = text
        bodyView.visibility = View.VISIBLE
    }

    fun setError(text: String?) {
        errorView.text = text ?: ""
        errorView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun Int.dp() = dp(this.toFloat()).toInt()

    private fun text(s: CharSequence, sp: Float, color: Int, face: Typeface) = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = face
    }

    private fun fieldParams() = LinearLayout.LayoutParams(dp(560f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = 24.dp()
    }

    private fun editText(context: Context, f: Field) = EditText(context).apply {
        setText(f.text)
        hint = f.hint
        setTextColor(theme.text)
        setHintTextColor(ColorUtils.setAlphaComponent(theme.text, 0x70))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = condensed
        isSingleLine = true
        minHeight = 64.dp()
        background = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(theme.accent, 0x26))
            setStroke(2.dp(), theme.accent)
            cornerRadius = dp(6f)
        }
        setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
        // No suggestions: Samsung's keyboard otherwise "predicts" into addresses (192.168.1.2 -> "10th").
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            if (f.password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            ) {
                f.onDone(); true
            } else false
        }
    }

    private fun button(context: Context, b: Button) = TextView(context).apply {
        text = b.label.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = condensedBold
        setTextColor(theme.onFill)
        // LCARS labels sit at the lower right of their pill; other themes centre them
        if (theme.frame == AppTheme.Frame.ELBOW) {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(40.dp(), 0, 24.dp(), 0)
        } else {
            gravity = Gravity.CENTER
            setPadding(28.dp(), 0, 28.dp(), 0)
        }
        minWidth = 160.dp()
        val fill = if (b.primary) theme.primary else theme.secondary
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pill(ColorUtils.blendARGB(fill, 0xFFFFFFFF.toInt(), 0.35f)))
            addState(intArrayOf(), pill(fill))
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { b.onClick() }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(theme.cornerRadiusDp)
    }
}

/**
 * The shell frame: a top bar joined to a left side bar by a rounded elbow, an end cap on the bar and a block
 * under the side bar. Sizes in dp.
 */
private class FrameDrawable(private val theme: AppTheme, private val dp: Float) : Drawable() {
    companion object {
        const val MARGIN = 16
        const val BAR = 36
        const val SIDE = 150
        const val CAP = 110
        const val GAP = 8
        const val BLOCK = 120
        const val OUTER_R = 64
        const val INNER_R = 28

        /** Where a screen's content starts inside its frame: left, top, right, bottom (dp). */
        fun contentInsets(frame: AppTheme.Frame): IntArray = when (frame) {
            AppTheme.Frame.ELBOW -> intArrayOf(SIDE + MARGIN + 36, BAR + MARGIN + 28, MARGIN + 36, MARGIN + 28)
            AppTheme.Frame.CHAMFER -> intArrayOf(MARGIN + 56, MARGIN + 40, MARGIN + 56, MARGIN + 40)
            AppTheme.Frame.CONSOLE -> intArrayOf(MARGIN + 40, MARGIN + 28 + 36, MARGIN + 40, MARGIN + 32)
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        when (theme.frame) {
            AppTheme.Frame.ELBOW -> drawElbow(canvas)
            AppTheme.Frame.CHAMFER -> drawChamfer(canvas)
            AppTheme.Frame.CONSOLE -> drawConsole(canvas)
        }
    }

    /** Elite: a thin outline with cut top-left and bottom-right corners, a filled tab on the top edge, a cyan tick. */
    private fun drawChamfer(canvas: Canvas) {
        val b = bounds
        val m = MARGIN * dp
        val l = b.left + m
        val t = b.top + m
        val r = b.right - m
        val bottom = b.bottom - m
        val cut = 36 * dp
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * dp
        paint.color = theme.primary
        path.reset()
        path.moveTo(l + cut, t)
        path.lineTo(r, t)
        path.lineTo(r, bottom - cut)
        path.lineTo(r - cut, bottom)
        path.lineTo(l, bottom)
        path.lineTo(l, t + cut)
        path.close()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        // the header tab: a slanted block on the top edge
        path.reset()
        path.moveTo(l + cut + 8 * dp, t)
        path.lineTo(l + cut + 300 * dp, t)
        path.lineTo(l + cut + 288 * dp, t + 12 * dp)
        path.lineTo(l + cut + 20 * dp, t + 12 * dp)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = theme.accent
        canvas.drawRect(r - 120 * dp, t + 6 * dp, r - 12 * dp, t + 10 * dp, paint)
    }

    /** Babylon 5: a thin bordered panel, an angled header tab top-left and an accent rule under it. */
    private fun drawConsole(canvas: Canvas) {
        val b = bounds
        val m = MARGIN * dp
        val l = b.left + m
        val t = b.top + m + 28 * dp
        val r = b.right - m
        val bottom = b.bottom - m
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * dp
        paint.color = theme.secondary
        canvas.drawRoundRect(RectF(l, t, r, bottom), 4 * dp, 4 * dp, paint)
        paint.style = Paint.Style.FILL
        paint.color = theme.primary
        path.reset()
        path.moveTo(l, t)
        path.lineTo(l, t - 28 * dp)
        path.lineTo(l + 260 * dp, t - 28 * dp)
        path.lineTo(l + 288 * dp, t)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = theme.accent
        canvas.drawRect(l + 300 * dp, t - 4 * dp, r - 24 * dp, t - 2 * dp, paint)
    }

    private fun drawElbow(canvas: Canvas) {
        val b = bounds
        val m = MARGIN * dp
        val l = b.left + m
        val t = b.top + m
        val r = b.right - m
        val bottom = b.bottom - m
        val bar = BAR * dp
        val side = SIDE * dp
        val gap = GAP * dp
        val capLeft = r - CAP * dp
        val sideBottom = bottom - BLOCK * dp - gap
        val outer = OUTER_R * dp
        val inner = INNER_R * dp

        // elbow: top bar + side bar
        path.reset()
        path.moveTo(l, t + outer)
        path.arcTo(RectF(l, t, l + 2 * outer, t + 2 * outer), 180f, 90f)
        path.lineTo(capLeft - gap, t)
        path.lineTo(capLeft - gap, t + bar)
        path.lineTo(l + side + inner, t + bar)
        path.arcTo(RectF(l + side, t + bar, l + side + 2 * inner, t + bar + 2 * inner), 270f, -90f)
        path.lineTo(l + side, sideBottom)
        path.lineTo(l, sideBottom)
        path.close()
        paint.color = theme.primary
        canvas.drawPath(path, paint)

        // end cap on the top bar
        paint.color = theme.secondary
        canvas.drawRoundRect(RectF(capLeft, t, r, t + bar), bar / 2, bar / 2, paint)
        canvas.drawRect(RectF(capLeft, t, capLeft + bar, t + bar), paint)

        // block under the side bar
        paint.color = theme.accent
        val blockR = minOf(outer, side / 2)
        path.reset()
        path.moveTo(l, sideBottom + gap)
        path.lineTo(l + side, sideBottom + gap)
        path.lineTo(l + side, bottom)
        path.lineTo(l + blockR, bottom)
        path.arcTo(RectF(l, bottom - 2 * blockR, l + 2 * blockR, bottom), 90f, 90f)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
