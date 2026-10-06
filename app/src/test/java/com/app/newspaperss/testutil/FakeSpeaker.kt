package com.app.newspaperss.testutil

import com.app.newspaperss.listen.Speaker

/**
 * A voice that says nothing: it records what it's asked to say, and the test plays the voice's
 * part by calling [startNext] and [finish].
 */
class FakeSpeaker : Speaker {
    override var listener: Speaker.Listener? = null
    data class Said(val id: String, val text: String, val language: String?, val rate: Float)

    /** Every line asked for, in order. */
    val said = mutableListOf<Said>()
    /** Lines queued and not yet finished, the one being said first. */
    val queue = ArrayDeque<Said>()
    /** Languages the voice has; anything else is "not installed". Null: all of them. */
    var installed: Set<String>? = null
    var released = false

    override fun speak(id: String, text: String, language: String?, rate: Float, flush: Boolean): Boolean {
        if (flush) queue.clear()
        val line = Said(id, text, language, rate)
        said += line
        queue += line
        return language == null || installed?.contains(language) != false
    }

    override fun stop() = queue.clear()

    override fun release() { released = true }

    /** The voice starts the line at the head of the queue; returns its text. */
    fun startNext(): String {
        val line = queue.first()
        listener?.onStart(line.id)
        return line.text
    }

    /** The line being said ends. */
    fun finish() {
        val line = queue.removeFirst()
        listener?.onDone(line.id)
    }

    fun fail() {
        val line = queue.removeFirst()
        listener?.onError(line.id)
    }

    /** Says the next line through: starts and finishes it. */
    fun sayNext(): String = startNext().also { finish() }
}
