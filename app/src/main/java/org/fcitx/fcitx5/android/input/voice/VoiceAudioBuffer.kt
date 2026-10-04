/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Session-owned audio storage. Dropping a prefix moves an index, not the remaining samples.
 * Compact only when an append needs space; snapshots own their data so native recognition and
 * asynchronous refinement cannot observe later writes. Pure Kotlin, unit-tested.
 */
internal class VoiceAudioBuffer(initialCapacity: Int = 16000 * 4) {
    private var data = FloatArray(initialCapacity.also { require(it > 0) })
    private var head = 0
    var size = 0
        private set

    fun append(src: FloatArray) {
        if (head + size + src.size > data.size) {
            val needed = size + src.size
            if (needed > data.size) {
                data = FloatArray(maxOf(data.size * 2, needed)).also {
                    data.copyInto(it, 0, head, head + size)
                }
            } else {
                data.copyInto(data, 0, head, head + size)
            }
            head = 0
        }
        src.copyInto(data, head + size)
        size += src.size
    }

    fun copyOfRange(from: Int, to: Int): FloatArray {
        require(from >= 0 && to >= from && to <= size)
        return data.copyOfRange(head + from, head + to)
    }

    /** Copy a complete VAD window into reusable storage, without allocating a new array. */
    fun copyInto(destination: FloatArray, from: Int) {
        require(from >= 0 && from + destination.size <= size)
        data.copyInto(destination, 0, head + from, head + from + destination.size)
    }

    fun toArray() = copyOfRange(0, size)

    fun dropFirst(n: Int) {
        require(n in 0..size)
        head += n
        size -= n
        if (size == 0) head = 0
    }
}
