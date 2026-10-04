package com.ultradisplay.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Liquid Glass design tokens and small view helpers. */
object Glass {
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_2 = 0xB8DCE4F5.toInt()
    const val TEXT_3 = 0x7AC9D3E8
    const val ACCENT = 0xFF5EE7F7.toInt()
    const val ACCENT_TINT = 0x4D38D6F0
    const val GREEN = 0xFF4ADE80.toInt()
    const val AMBER = 0xFFFBBF24.toInt()
    const val RED = 0xFFF87171.toInt()
    const val RED_TINT = 0x4DF05A6E
    const val GREY = 0xFF94A3B8.toInt()
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.label(text: String, sizeSp: Float, bold: Boolean = false, color: Int = Glass.TEXT): TextView =
    TextView(this).apply {
        this.text = text; textSize = sizeSp; setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

fun Context.glassCard(radiusDp: Float = 26f, padDp: Int = 18): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = GlassDrawable(this@glassCard, dp(radiusDp))
    setPadding(dpi(padDp), dpi(padDp), dpi(padDp), dpi(padDp))
}

fun Context.glassButton(text: String, radiusDp: Float = 22f, sizeSp: Float = 16f, tint: Int = 0): TextView = label(text, sizeSp, true).apply {
    gravity = Gravity.CENTER
    textAlignment = View.TEXT_ALIGNMENT_CENTER
    background = GlassDrawable(this@glassButton, dp(radiusDp)).also { it.glassTint = tint }
    setPadding(dpi(14), dpi(14), dpi(14), dpi(14))
    isClickable = true
    pressable()
}

fun Context.dot(color: Int, sizeDp: Int = 10): View = View(this).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
}

/** Springy press feedback, like glass being pushed. Keeps click handling intact. */
fun View.pressable() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.965f).scaleY(0.965f).setDuration(90).setInterpolator(null).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.4f)).start()
        }
        false
    }
}

/**
 * Translucent glass panel: frosted white fill, specular highlight on the top edge,
 * and a light-catching rim that fades toward the bottom.
 */
class GlassDrawable(context: Context, private val radius: Float) : Drawable() {
    private val strokeW = context.dp(1.1f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokeW }
    private val rect = RectF()
    private val shineMax = context.dp(70f)

    var glassTint: Int = 0
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds); rect.inset(strokeW / 2, strokeW / 2)
        fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0x2BFFFFFF, 0x12FFFFFF, 0x1CFFFFFF), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        shine.shader = LinearGradient(0f, rect.top, 0f, rect.top + min(rect.height() * .55f, shineMax),
            intArrayOf(0x24FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(rect.left, rect.top, rect.right * .4f + rect.left * .6f, rect.bottom,
            intArrayOf(0x9EFFFFFF.toInt(), 0x1FFFFFFF, 0x45FFFFFF), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val r = min(radius, rect.height() / 2)
        canvas.drawRoundRect(rect, r, r, fill)
        if (glassTint != 0) { tintPaint.color = glassTint; canvas.drawRoundRect(rect, r, r, tintPaint) }
        canvas.drawRoundRect(rect, r, r, shine)
        canvas.drawRoundRect(rect, r, r, rim)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; rim.alpha = alpha; shine.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Slowly drifting colour blobs under the glass, so the panels have something to refract. */
class LiquidBackground(context: Context) : View(context) {
    private class Blob(val color: Int, val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)
    private val blobs = listOf(
        Blob(0xB322D3EE.toInt(), .15f, .12f, .55f, 1f, 0f),
        Blob(0xA67C3AED.toInt(), .90f, .30f, .60f, -.8f, .3f),
        Blob(0x8CEC4899.toInt(), .20f, .75f, .50f, .7f, .6f),
        Blob(0x993B82F6.toInt(), .85f, .92f, .55f, -1.1f, .15f)
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = Paint()
    private var t = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 30_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        base.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF060913.toInt(), 0xFF0C1230.toInt(), 0xFF070B18.toInt()), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, base)
        val big = max(w, h)
        for (b in blobs) {
            val a = (2 * Math.PI * (t * b.speed + b.phase)).toFloat()
            val cx = w * (b.x + .16f * sin(a)); val cy = h * (b.y + .10f * cos(a * .8f))
            val r = big * b.r
            paint.shader = RadialGradient(cx, cy, r, intArrayOf(b.color, b.color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
        // Soft vignette keeps the text readable at the edges.
        paint.shader = RadialGradient(w / 2, h * .4f, big * .85f, intArrayOf(0x00000000, 0x66000000), floatArrayOf(.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
    }
}
