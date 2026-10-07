package com.voicereact.app.page3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch

/**
 * Sits between Filament's render target and the MediaCodec input surface: receives each
 * rendered 3D frame via an offscreen Bitmap readback, draws the animated watermark on top
 * with Canvas, then pushes the composited frame to the encoder's input surface.
 */
class WatermarkCompositeSurface(
    private val width: Int,
    private val height: Int,
    private val encoderSurface: Surface,
    context: android.content.Context
) {
    private val watermark = WatermarkOverlay(context, width, height)
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    /** Call once per frame after Filament has rendered into [sourceBitmap] (a readback of
     *  the GPU frame). Composites it with the watermark and presents it on the encoder surface. */
    fun composite(sourceBitmap: Bitmap, timeSeconds: Float) {
        canvas.drawBitmap(sourceBitmap, 0f, 0f, null)
        watermark.draw(canvas, timeSeconds)

        val surfaceCanvas = encoderSurface.lockCanvas(null)
        try {
            surfaceCanvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            encoderSurface.unlockCanvasAndPost(surfaceCanvas)
        }
    }
}
