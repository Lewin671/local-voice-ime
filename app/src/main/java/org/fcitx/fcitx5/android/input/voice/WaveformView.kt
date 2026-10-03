/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import androidx.annotation.ColorInt
import splitties.dimensions.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The one moving element of the voice UI: a row of bars that follows the microphone level.
 *
 * Spec (`docs/design/DESIGN.md`): 27 bars, 3 dp wide, 3 dp gap, 4–56 dp tall.
 */
class WaveformView(context: Context) : View(context) {

    enum class Mode {
        /** Bars follow [level]. */
        Live,

        /** Low dots: the microphone is off. */
        Idle,

        /** Flat bars: about to cancel. */
        Flat
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val barWidth = dp(3).toFloat()
    private val barGap = dp(3).toFloat()
    private val minHeight = dp(4).toFloat()
    private val maxHeight = dp(56).toFloat()

    var mode = Mode.Idle
        set(value) {
            if (field == value) return
            field = value
            paint.alpha = if (value == Mode.Idle) 0x73 else 0xff
            invalidate()
        }

    @get:ColorInt
    var color: Int
        get() = paint.color
        set(value) {
            val alpha = paint.alpha
            paint.color = value
            paint.alpha = alpha
            invalidate()
        }

    /** Microphone level in [0, 1]. */
    var level = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (mode == Mode.Live && (field > 0.01f || shown > 0.01f)) scheduleFrame()
        }

    // The bars are redrawn about 30 times per second, not at the refresh rate of the display:
    // animating at 120 Hz for as long as the microphone is on costs energy for a difference
    // nobody sees in bars that ease over 80-250 ms, and keeps the panel from slowing down.
    private var frameScheduled = false
    private val frame = Runnable {
        frameScheduled = false
        invalidate()
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        postDelayed(frame, FRAME_INTERVAL_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    // what is drawn: follows `level` quickly on the way up and slowly on the way down
    private var shown = 0f
    private var lastFrame = 0L

    // bell-shaped envelope, so that the row is tallest in the middle
    private val envelope = FloatArray(BARS) { i ->
        val x = (i - (BARS - 1) / 2f) / (BARS / 2f)
        exp(-2.2f * x * x)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (BARS * barWidth + (BARS - 1) * barGap).toInt() + paddingLeft + paddingRight
        val h = maxHeight.toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 100L)
        lastFrame = now
        if (mode == Mode.Live) {
            // ~80 ms attack, ~250 ms release
            val rate = if (level > shown) dt / 80f else dt / 250f
            shown += (level - shown) * rate.coerceAtMost(1f)
        } else {
            shown = 0f
        }

        val available = minOf(maxHeight, (height - paddingTop - paddingBottom).toFloat())
        val total = BARS * barWidth + (BARS - 1) * barGap
        var x = (width - total) / 2f
        val centerY = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val t = now / 1000.0
        for (i in 0 until BARS) {
            // each bar wobbles at its own pace, so the row never looks like a single shape
            val wobble = 0.65f + 0.35f * abs(sin(t * (5.0 + i % 5) + i * PI / 3.0)).toFloat()
            val h = minHeight + (available - minHeight) * shown * envelope[i] * wobble
            canvas.drawRoundRect(
                x, centerY - h / 2f, x + barWidth, centerY + h / 2f,
                barWidth / 2f, barWidth / 2f, paint
            )
            x += barWidth + barGap
        }
        if (mode == Mode.Live && (shown > 0.01f || level > 0.01f)) scheduleFrame()
    }

    companion object {
        private const val BARS = 27

        // just under two frames at 60 Hz (three at 90 Hz, four at 120 Hz)
        private const val FRAME_INTERVAL_MS = 30L
    }
}
