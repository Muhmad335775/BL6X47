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
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

class VrmTestActivity : Activity() {

    companion object {
        // CRITICAL: this must run once before any Filament/ModelViewer use,
        // or every call into the rendering engine crashes immediately.
        private var filamentInitialized = false
        private fun ensureFilamentInit(): Boolean {
            if (filamentInitialized) return true
            return try {
                Utils.init()
                filamentInitialized = true
                true
            } catch (e: Throwable) {
                false
            }
        }
    }

    private var surfaceView: SurfaceView? = null
    private var choreographer: Choreographer? = null
    private var modelViewer: ModelViewer? = null

    private var mouthEntity: Int = 0
    private var mouthMorphIndex: Int = -1
    private var morphCount: Int = 0

    @Volatile private var liveAmplitude: Float = 0f
    @Volatile private var micRunning = false
    private var micThread: Thread? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer?.postFrameCallback(this)
            try {
                applyMouthWeight()
                modelViewer?.render(frameTimeNanos)
            } catch (e: Throwable) {
                // Never let a per-frame render error crash the app
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ensureFilamentInit()) {
            Toast.makeText(this, "3D engine failed to start on this device", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            val sv = SurfaceView(this)
            surfaceView = sv
            setContentView(sv)

            choreographer = Choreographer.getInstance()
            modelViewer = ModelViewer(sv)

            loadFixedModel("1m/m.vrm")

            if (hasMicPermission()) {
                startMicListening()
            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), 42
                )
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Could not start 3D view: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
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
        val viewer = modelViewer ?: return
        try {
            val bytes = assets.open(assetPath).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes)
            buffer.rewind()
            viewer.loadModelGlb(buffer)
            viewer.transformToUnitCube()
            findMouthMorphTarget()
        } catch (e: Throwable) {
            uiHandler.post {
                Toast.makeText(this, "Could not load model: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun findMouthMorphTarget() {
        val viewer = modelViewer ?: return
        try {
            val asset = viewer.asset ?: return
            val rm = viewer.engine.renderableManager
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
        } catch (e: Throwable) {
            // Model has no matching blend shape name — mouth just won't animate, no crash
        }
    }

    private fun applyMouthWeight() {
        val viewer = modelViewer ?: return
        if (mouthMorphIndex < 0 || morphCount <= 0) return
        try {
            val rm = viewer.engine.renderableManager
            val instance = rm.getInstance(mouthEntity)
            val weights = FloatArray(morphCount)
            weights[mouthMorphIndex] = liveAmplitude.coerceIn(0f, 1f)
            rm.setMorphWeights(instance, weights, 0)
        } catch (e: Throwable) {
        }
    }

    private fun startMicListening() {
        if (micRunning) return
        micRunning = true
        micThread = thread(start = true) {
            val sampleRate = 44100
            val minBuf = try {
                AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
            } catch (e: Exception) {
                -1
            }
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
                        liveAmplitude = liveAmplitude * 0.5f + normalized * 0.5f
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
        try {
            choreographer?.postFrameCallback(frameCallback)
        } catch (e: Throwable) {
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            choreographer?.removeFrameCallback(frameCallback)
        } catch (e: Throwable) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMicListening()
        try {
            modelViewer?.destroyModel()
        } catch (e: Throwable) {
        }
    }
}
