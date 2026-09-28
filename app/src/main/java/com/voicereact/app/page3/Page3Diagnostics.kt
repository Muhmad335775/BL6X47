package com.voicereact.app.page3

enum class IndicatorState { IDLE, WORKING, OK, FAIL }

class Page3Diagnostics(private val onChanged: (Page3Diagnostics) -> Unit) {

    @Volatile var audioState: IndicatorState = IndicatorState.IDLE
    @Volatile var audioText: String = "الصوت: بانتظار البدء"

    @Volatile var bodyState: IndicatorState = IndicatorState.IDLE
    @Volatile var bodyText: String = "حركة الجسد: بانتظار البدء"

    @Volatile var renderState: IndicatorState = IndicatorState.IDLE
    @Volatile var renderText: String = "الرندر: بانتظار البدء"

    @Volatile
    var progress: Int = 0
        private set

    @Volatile var finalVideoPath: String? = null

    private val logBuffer = StringBuilder()

    fun setAudio(state: IndicatorState, text: String) {
        audioState = state
        audioText = text
        onChanged(this)
    }

    fun setBody(state: IndicatorState, text: String) {
        bodyState = state
        bodyText = text
        onChanged(this)
    }

    fun setRender(state: IndicatorState, text: String) {
        renderState = state
        renderText = text
        onChanged(this)
    }

    fun setProgress(value: Int) {
        progress = value
        onChanged(this)
    }

    fun log(line: String) {
        synchronized(logBuffer) {
            logBuffer.append(line).append('\n')
            if (logBuffer.length > 9000) logBuffer.delete(0, logBuffer.length - 7000)
        }
        onChanged(this)
    }

    fun logText(): String = synchronized(logBuffer) { logBuffer.toString() }
}
