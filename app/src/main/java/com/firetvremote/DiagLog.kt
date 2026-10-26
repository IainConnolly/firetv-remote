package com.firetvremote

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log of what the service sees, shown on the Diagnostics screen. */
object DiagLog {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(message: String) {
        lines.addLast("${format.format(Date())}  $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}
