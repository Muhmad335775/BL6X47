package com.voicereact.app.page3

import com.google.android.filament.Engine

/**
 * Root-cause fix for the intermittent NullPointerException every 5-7 videos:
 * create ONE Engine for the whole app process and reuse it, instead of a fresh
 * Engine.create()/destroy() cycle per video (which leaked EGL contexts on many
 * GPU drivers until Android's context limit was hit).
 */
object FilamentEngineHolder {

    @Volatile
    private var engine: Engine? = null

    @Synchronized
    fun get(): Engine {
        var e = engine
        if (e == null) {
            e = Engine.create()
            engine = e
        }
        return e
    }
}
