// STAGE 1 TEST: loads a fixed VRM model (1m/m.vrm from assets) full-screen,
// and drives its mouth-open blend shape live from the phone's mic volume.
package com.voicereact.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.filament.utils.ModelViewer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class VrmTestActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var choreographer: Choreographer
    private lateinit var modelViewer: ModelViewer

    private var mouthEntity: Int = 0
    private var mouthMorphIndex: Int = -1
    private var morphCount: Int = 0

    @Volatile private var liveAmplitude: Float = 0f
    @Volatile private var micRunning = false
    private var micThread: Thread? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            applyMouthWeight()
            modelViewer.render(frameTimeNanos)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        choreographer = Choreographer.getInstance()
        modelViewer = ModelViewer(surfaceView)

        loadFixedModel("1m/m.vrm")

        if (hasMicPermission()) {
            startMicListening()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 42
            )
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && hasMicPermission()) {
            startMicListening()
        }
    }

    private fun loadFixedModel(assetPath: String) {
        try {
            val bytes = assets.open(assetPath).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes)
            buffer.rewind()
            modelViewer.loadModelGlb(buffer)
            modelViewer.transformToUnitCube()
            findMouthMorphTarget()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load model: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Locates the model's own "A" (open mouth) blend shape, per the standard
    // VRM viseme set (A/I/U/E/O) — same name on every one of the 15 models.
    private fun findMouthMorphTarget() {
        val asset = modelViewer.asset ?: return
        val rm = modelViewer.engine.renderableManager
        for (entity in asset.entities) {
            if (!rm.hasComponent(entity)) continue
            val names = try {
                asset.getMorphTargetNames(entity)
            } catch (e: Exception) {
                emptyArray()
            }
            val idx = names.indexOfFirst {
                it.equals("A", ignoreCase = true) || it.contains("mouth", ignoreCase = true)
            }
            if (idx >= 0) {
                mouthEntity = entity
                mouthMorphIndex = idx
                val instance = rm.getInstance(entity)
                morphCount = rm.getMorphTargetCount(instance)
                return
            }
        }
    }

    private fun applyMouthWeight() {
        if (mouthMorphIndex < 0 || morphCount <= 0) return
        val rm = modelViewer.engine.renderableManager
        val instance = rm.getInstance(mouthEntity)
        val weights = FloatArray(morphCount)
        weights[mouthMorphIndex] = liveAmplitude.coerceIn(0f, 1f)
        rm.setMorphWeights(instance, weights, 0)
    }

    // Live mic volume -> normalized 0..1 amplitude, read continuously on a
    // background thread. No file is written here; this is real-time only.
    private fun startMicListening() {
        if (micRunning) return
        micRunning = true
        micThread = thread(start = true) {
            val sampleRate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                micRunning = false
                return@thread
            }
            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                record.startRecording()
                val buffer = ShortArray(minBuf)
                while (micRunning) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += (buffer[i] * buffer[i]).toDouble()
                        }
                        val rms = sqrt(sum / read)
                        val normalized = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
                        liveAmplitude = liveAmplitude * 0.5f + normalized * 0.5f // light smoothing
                    }
                }
            } catch (e: Exception) {
                uiHandler.post {
                    Toast.makeText(this, "Mic error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                try { record?.stop() } catch (e: Exception) {}
                try { record?.release() } catch (e: Exception) {}
            }
        }
    }

    private fun stopMicListening() {
        micRunning = false
        micThread = null
    }

    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMicListening()
    }
}
