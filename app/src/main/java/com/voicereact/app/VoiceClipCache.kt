package com.voicereact.app

import android.content.Context
import java.io.File

object VoiceClipCache {

    /** All recordings live under cacheDir, not filesDir — Android can reclaim this space
     *  automatically under storage pressure, and it never counts toward the app's
     *  permanent/visible storage usage to the user. */
    fun directory(context: Context): File {
        val dir = File(context.cacheDir, "voice_clips")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun newClipFile(context: Context): File {
        val dir = directory(context)
        return File(dir, "clip_${System.currentTimeMillis()}.m4a")
    }

    /** Call this periodically (e.g. on app start, or after N clips) to keep cache size bounded. */
    fun trimToMaxCount(context: Context, maxCount: Int) {
        val dir = directory(context)
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= maxCount) return
        val toDelete = files.size - maxCount
        for (i in 0 until toDelete) {
            try { files[i].delete() } catch (ignored: Throwable) { }
        }
    }

    fun clearAll(context: Context) {
        directory(context).listFiles()?.forEach { try { it.delete() } catch (ignored: Throwable) { } }
    }
}
