package com.thor.instantreplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Static background: animation is reserved for focus and page changes. */
class ReplayBackdropView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(22, 202, 204))
        val u = resources.displayMetrics.density
        paint.color = Color.argb(22, 0, 66, 74)
        var y = 0f
        while (y < height) {
            var x = 0f
            while (x < width) {
                path.reset()
                path.moveTo(x + 9*u, y); path.lineTo(x + 18*u, y + 9*u)
                path.lineTo(x + 9*u, y + 18*u); path.lineTo(x, y + 9*u); path.close()
                canvas.drawPath(path, paint); x += 28*u
            }
            y += 28*u
        }
        paint.color = Color.rgb(221, 255, 46)
        path.reset(); path.moveTo(0f, 0f); path.lineTo(width*.62f,0f)
        path.lineTo(width*.58f,66*u); path.lineTo(0f,66*u); path.close()
        canvas.drawPath(path,paint)
        paint.color = Color.rgb(255, 52, 158)
        path.reset(); path.moveTo(266*u,75*u); path.lineTo(273*u,75*u)
        path.lineTo(251*u,height-42*u); path.lineTo(244*u,height-42*u); path.close()
        canvas.drawPath(path,paint)
    }
}
