package com.thor.instantreplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.LinearLayout

/** Native cut-corner panels and clip rows; every stroke is drawn inside its bounds. */
class ReplayPanelLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    init { background = ReplayCutDrawable(resources.displayMetrics.density, tag == "poster") }
}

class ReplayCutDrawable(private val density: Float, private val poster: Boolean = false,
                        private val interactive: Boolean = false) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var focused = false
    private var opacity = 255
    override fun isStateful() = interactive
    override fun onStateChange(state: IntArray): Boolean {
        val next = state.contains(android.R.attr.state_focused)
        if (next == focused) return false
        focused = next; invalidateSelf(); return true
    }
    override fun draw(canvas: Canvas) {
        val inset = 1.5f * density
        val left = bounds.left + inset; val top = bounds.top + inset
        val right = bounds.right - inset; val bottom = bounds.bottom - inset
        val cut = minOf((if (poster) 16 else 10) * density, (bottom-top)/3)
        path.reset(); path.moveTo(left,top); path.lineTo(right-cut,top); path.lineTo(right,top+cut)
        path.lineTo(right,bottom); path.lineTo(left+cut,bottom); path.lineTo(left,bottom-cut); path.close()
        paint.style = Paint.Style.FILL; paint.color = if (focused) Color.rgb(228,244,60) else Color.rgb(33,31,48)
        paint.alpha = opacity; canvas.drawPath(path,paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = (if(focused||poster) 2.5f else 1f)*density
        paint.color = if(focused||poster) Color.rgb(255,71,149) else Color.rgb(55,91,103)
        paint.alpha = opacity; canvas.drawPath(path,paint)
        paint.style = Paint.Style.FILL; paint.color = if(focused) Color.rgb(33,31,48) else Color.rgb(228,244,60)
        paint.alpha = opacity
        canvas.drawRect(left+12*density,top,left+(if(poster) 60 else 34)*density,top+2*density,paint)
    }
    override fun setAlpha(alpha: Int) { opacity=alpha;invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
