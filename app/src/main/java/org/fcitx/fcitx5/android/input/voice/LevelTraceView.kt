/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.annotation.ColorInt
import splitties.dimensions.dp
import kotlin.math.pow

/**
 * The waveform of the dictation strip: a trace of what the microphone heard in the last few
 * seconds. New sound enters on the right and moves left, fading out.
 *
 * Spec (`docs/design/DESIGN.md`): bars 1.5 dp wide, 4 dp from one to the next, 1.5–16 dp tall,
 * the left half fading to nothing. Ordinary speech reaches about half the height: a row of
 * bars that all touch the limit says nothing about the voice.
 */
class LevelTraceView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val barWidth = dp(1.5f)
    private val step = dp(4).toFloat()
    private val minHeight = dp(1.5f)
    private val maxHeight = dp(16).toFloat()

    @get:ColorInt
    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    // the newest bar is at `head`; older ones before it, wrapping around
    private val levels = FloatArray(CAPACITY)
    private var head = 0

    /** How many bars are not at rest: with none, and silence, nothing needs to be drawn again. */
    private var loud = 0

    // moves the trace by half a bar between two bars, so that it glides rather than jumps
    private var half = false

    // follows `level` quickly on the way up and slowly on the way down
    private var shown = 0f

    /** Whether the microphone is on. When it goes off, the trace is cleared. */
    var live = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                levels.fill(0f)
                loud = 0
                shown = 0f
                level = 0f
                removeCallbacks(frame)
                scheduled = false
                invalidate()
            }
        }

    /** Microphone level in [0, 1]. */
    var level = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (live && field > QUIET) schedule()
        }

    // About 30 frames per second, not the refresh rate of the display, and none at all while
    // nobody speaks and the trace has run out: the microphone can be on for a long time.
    private var scheduled = false
    private val frame = Runnable {
        scheduled = false
        advance()
        invalidate()
        if (live && (loud > 0 || level > QUIET || shown > QUIET)) schedule()
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        postDelayed(frame, FRAME_INTERVAL_MS)
    }

    private fun advance() {
        shown += (level - shown) * if (level > shown) 0.6f else 0.25f
        half = !half
        if (half) return
        head = (head + 1) % CAPACITY
        if (levels[head] > QUIET) loud--
        // leaves headroom: only a raised voice comes near the full height
        val bar = if (shown > QUIET) 0.9f * shown.pow(1.8f) else 0f
        levels[head] = bar
        if (bar > QUIET) loud++
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        scheduled = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = paint.alpha
        val centerY = height / 2f
        val available = minOf(maxHeight, height.toFloat())
        val bars = minOf(CAPACITY, (width / step).toInt() + 1)
        var x = width - barWidth - if (half) step / 2f else 0f
        for (i in 0 until bars) {
            if (x < 0f) break
            val h = minHeight + (available - minHeight) * levels[(head - i + CAPACITY) % CAPACITY]
            // full strength on the right half, fading out towards the left edge
            val strength = (2f * x / width).coerceIn(0f, 1f)
            paint.alpha = (alpha * strength).toInt()
            canvas.drawRoundRect(
                x, centerY - h / 2f, x + barWidth, centerY + h / 2f,
                barWidth / 2f, barWidth / 2f, paint
            )
            x -= step
        }
        paint.alpha = alpha
    }

    private companion object {
        // more than fit on any phone held upright or sideways
        const val CAPACITY = 256
        const val QUIET = 0.01f
        const val FRAME_INTERVAL_MS = 33L
    }
}
