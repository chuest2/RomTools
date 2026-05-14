package com.chuest.romtools.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private const val TAG = "RomTools"
    private val timeFmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US)

    enum class Level { INFO, NOTICE, WARN, ERROR }

    data class Line(val ts: Long, val level: Level, val text: String) {
        fun render(): String = "${timeFmt.format(Date(ts))} [${level.name}] $text"
    }

    private val _flow = MutableSharedFlow<Line>(extraBufferCapacity = 1024)
    val flow: SharedFlow<Line> = _flow

    fun i(text: String) = post(Level.INFO, text)
    fun n(text: String) = post(Level.NOTICE, text)
    fun w(text: String) = post(Level.WARN, text)
    fun e(text: String, t: Throwable? = null) {
        post(Level.ERROR, if (t == null) text else "$text :: ${t.javaClass.simpleName}: ${t.message}")
        if (t != null) Log.e(TAG, text, t)
    }

    private fun post(lvl: Level, text: String) {
        val line = Line(System.currentTimeMillis(), lvl, text)
        Log.println(
            when (lvl) {
                Level.ERROR -> Log.ERROR
                Level.WARN -> Log.WARN
                else -> Log.INFO
            }, TAG, text
        )
        _flow.tryEmit(line)
    }
}
