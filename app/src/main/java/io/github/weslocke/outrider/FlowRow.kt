package io.github.weslocke.outrider

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Lays its children out left to right and wraps onto further lines when they don't fit, so a row of buttons
 * never squeezes the last ones to nothing (six menu buttons, four long saved addresses, a narrow phone).
 */
class FlowRow(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        var x = 0
        var lineHeight = 0
        var height = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams
            // a child is never wider than the row
            val childWidthSpec = if (unbounded) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                else MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            val childHeightSpec = if (lp.height > 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            c.measure(childWidthSpec, childHeightSpec)
            if (x > 0 && !unbounded && x + hGap + c.measuredWidth > maxWidth) {
                height += lineHeight + vGap
                x = 0
                lineHeight = 0
            }
            x += (if (x > 0) hGap else 0) + c.measuredWidth
            widest = max(widest, x)
            lineHeight = max(lineHeight, c.measuredHeight)
        }
        height += lineHeight
        val w = if (unbounded) widest + paddingLeft + paddingRight else MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, height + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + hGap + c.measuredWidth > maxWidth) {
                y += lineHeight + vGap
                x = 0
                lineHeight = 0
            }
            if (x > 0) x += hGap
            c.layout(paddingLeft + x, y, paddingLeft + x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth
            lineHeight = max(lineHeight, c.measuredHeight)
        }
    }
}
