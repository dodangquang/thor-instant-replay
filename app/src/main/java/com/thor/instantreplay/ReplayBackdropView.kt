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
        canvas.drawColor(Color.rgb(15, 188, 193))
        val u = resources.displayMetrics.density
        paint.color = Color.argb(24, 0, 66, 74)
        var y = 0f
        while (y < height) {
            var x = 0f
            while (x < width) {
                canvas.drawCircle(x,y,2*u,paint); x += 18*u
            }
            y += 18*u
        }
        paint.color = Color.argb(35, 0, 85, 96)
        path.reset();path.moveTo(width*.69f,0f);path.lineTo(width*.89f,0f)
        path.lineTo(width*.65f,height.toFloat());path.lineTo(width*.45f,height.toFloat());path.close()
        canvas.drawPath(path,paint)
        paint.color = Color.rgb(247,255,255)
        val headerWidth=minOf(308*u,width*.47f)
        path.reset(); path.moveTo(0f, 8*u); path.lineTo(headerWidth,8*u)
        path.lineTo(headerWidth-15*u,60*u); path.lineTo(0f,60*u); path.close()
        canvas.drawPath(path,paint)
        paint.color = Color.rgb(255, 71, 149)
        path.reset(); path.moveTo(262*u,68*u); path.lineTo(269*u,68*u)
        path.lineTo(248*u,height-28*u); path.lineTo(241*u,height-28*u); path.close()
        canvas.drawPath(path,paint)
        paint.color=Color.rgb(33,31,48)
        canvas.drawRect(0f,height-28*u,width.toFloat(),height.toFloat(),paint)
    }
}
