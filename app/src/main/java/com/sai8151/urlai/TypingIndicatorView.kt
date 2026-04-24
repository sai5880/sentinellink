package com.sai8151.urlai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * TypingIndicatorView
 *
 * Draws three dots in a wave pattern (each dot scales + translates up/down
 * with a staggered delay) and a soft colour-cycling glow behind them.
 *
 * Usage: drop into any layout. Call startAnimation() / stopAnimation() as needed.
 * The view exposes no required attributes — it works out of the box.
 */
class TypingIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Geometry ──────────────────────────────────────────────────────────────

    private val dotCount   = 3
    private val dotRadius  = dp(5f)
    private val dotSpacing = dp(10f)   // centre-to-centre

    // ── Paint ─────────────────────────────────────────────────────────────────

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 0
    }
    private val glowRect = RectF()

    // ── Animation state ───────────────────────────────────────────────────────

    // translateY offset for each dot (animated)
    private val dotOffsets = FloatArray(dotCount) { 0f }
    // scale for each dot
    private val dotScales  = FloatArray(dotCount) { 1f }
    // colour sweep value [0,1] for the glow gradient
    private var glowAlpha  = 0f

    private var animatorSet: AnimatorSet? = null
    private var glowAnimator: ValueAnimator? = null
    private var isRunning = false

    // ── Sizing ────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth  = (dotCount * dotRadius * 2 + (dotCount - 1) * (dotSpacing - dotRadius * 2) + dp(24f)).toInt()
        val totalHeight = (dotRadius * 2 + dp(18f)).toInt()
        setMeasuredDimension(
            resolveSize(totalWidth, widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec)
        )
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        // Glow background pill
        if (glowAlpha > 0) {
            val pillW = dotCount * dotSpacing + dp(8f)
            val pillH = dotRadius * 2 + dp(12f)
            glowRect.set(cx - pillW / 2, cy - pillH / 2, cx + pillW / 2, cy + pillH / 2)
            LinearGradient(
                glowRect.left, glowRect.top, glowRect.right, glowRect.bottom,
                intArrayOf(
                    Color.argb((glowAlpha * 60).toInt(), 100, 180, 255),
                    Color.argb((glowAlpha * 30).toInt(), 180, 100, 255),
                    Color.argb(0, 0, 0, 0)
                ),
                null,
                Shader.TileMode.CLAMP
            ).also { glowPaint.shader = it }
            canvas.drawRoundRect(glowRect, pillH / 2, pillH / 2, glowPaint)
        }

        // Dots
        val startX = cx - (dotCount - 1) / 2f * dotSpacing
        for (i in 0 until dotCount) {
            val x = startX + i * dotSpacing
            val y = cy + dotOffsets[i]
            val r = dotRadius * dotScales[i]

            // Soft glow halo on each dot
            val haloAlpha = ((1f - dotOffsets[i] / -dp(6f)).coerceIn(0f, 1f) * 60).toInt()
            dotPaint.setShadowLayer(dp(6f), 0f, 0f, Color.argb(haloAlpha, 150, 200, 255))

            dotPaint.color = lerpColor(
                Color.parseColor("#888888"),
                Color.parseColor("#DDDDDD"),
                (1f - dotOffsets[i] / -dp(6f)).coerceIn(0f, 1f)
            )
            canvas.drawCircle(x, y, r, dotPaint)
        }
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    fun startAnimation() {
        if (isRunning) return
        isRunning = true
        visibility = VISIBLE

        val set = AnimatorSet()
        val animators = mutableListOf<Animator>()

        for (i in 0 until dotCount) {
            val delay = i * 160L

            // Translate up and back
            val translateUp = ValueAnimator.ofFloat(0f, -dp(7f), 0f).apply {
                duration = 600
                startDelay = delay
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    dotOffsets[i] = it.animatedValue as Float
                    invalidate()
                }
            }

            // Scale pulse: shrink slightly at top of arc, overshoot on land
            val scale = ValueAnimator.ofFloat(1f, 0.75f, 1.15f, 1f).apply {
                duration = 600
                startDelay = delay
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    dotScales[i] = it.animatedValue as Float
                }
            }

            animators += translateUp
            animators += scale
        }

        // Glow breathe
        val glowAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                glowAlpha = it.animatedValue as Float
                invalidate()
            }
        }
        animators += glowAnim

        set.playTogether(animators)
        set.start()
        animatorSet = set
    }

    fun stopAnimation() {
        isRunning = false
        animatorSet?.cancel()
        animatorSet = null
        glowAnimator?.cancel()
        // Fade out the view
        animate()
            .alpha(0f)
            .setDuration(250)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                    alpha = 1f
                }
            })
            .start()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isRunning) startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animatorSet?.cancel()
        glowAnimator?.cancel()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a)   + (Color.red(b)   - Color.red(a))   * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b)  - Color.blue(a))  * t).toInt()
        return Color.rgb(r, g, bl)
    }
}