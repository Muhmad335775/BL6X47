package com.voicereact.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Skybox
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class VrmTestActivity : Activity() {

    companion object {
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

    private var mouthTargets: MutableList<Triple<Int, Int, Int>> = mutableListOf()

    @Volatile private var liveAmplitude: Float = 0f
    @Volatile private var micRunning = false
    private var micThread: Thread? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val startTimeNanos = System.nanoTime()

    // Per-character palette so the moving background differs per pick
    private var paletteA = floatArrayOf(0.10f, 0.09f, 0.16f)
    private var paletteB = floatArrayOf(0.20f, 0.10f, 0.24f)

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer?.postFrameCallback(this)
            try {
                updateAnimatedBackground(frameTimeNanos)
                applyMouthWeight()
                modelViewer?.render(frameTimeNanos)
            } catch (e: Throwable) {
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

            val pickedNumber = Random.nextInt(1, 16)
            setPaletteForCharacter(pickedNumber)
            addSceneLighting()

            loadFixedModel("${pickedNumber}m/m.vrm")

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

    // Different color range per character number, so backgrounds vary
    // model to model, not just one repeated flat wall color.
    private fun setPaletteForCharacter(number: Int) {
        val palettes = listOf(
            floatArrayOf(0.12f, 0.08f, 0.20f) to floatArrayOf(0.30f, 0.10f, 0.28f), // purple/magenta
            floatArrayOf(0.05f, 0.12f, 0.20f) to floatArrayOf(0.10f, 0.28f, 0.30f), // teal/blue
            floatArrayOf(0.20f, 0.10f, 0.06f) to floatArrayOf(0.32f, 0.20f, 0.08f), // warm orange
            floatArrayOf(0.06f, 0.16f, 0.10f) to floatArrayOf(0.14f, 0.30f, 0.18f), // green
            floatArrayOf(0.16f, 0.06f, 0.16f) to floatArrayOf(0.30f, 0.12f, 0.22f)  // pink/rose
        )
        val (a, b) = palettes[number % palettes.size]
        paletteA = a
        paletteB = b
    }

    // Live-animated background: smoothly cross-fades between two colors on
    // a slow sine wave every frame — never a single static wall color.
    private fun updateAnimatedBackground(frameTimeNanos: Long) {
        val viewer = modelViewer ?: return
        try {
            val elapsedSeconds = (frameTimeNanos - startTimeNanos) / 1_000_000_000.0
            val t = (sin(elapsedSeconds * 0.5) * 0.5 + 0.5).toFloat() // 0..1 oscillation
            val r = paletteA[0] + (paletteB[0] - paletteA[0]) * t
            val g = paletteA[1] + (paletteB[1] - paletteA[1]) * t
            val b = paletteA[2] + (paletteB[2] - paletteA[2]) * t

            val renderer: Renderer = viewer.renderer
            val options = renderer.clearOptions
            options.clearColor = floatArrayOf(r, g, b, 1.0f)
            options.clear = true
            renderer.clearOptions = options
        } catch (e: Throwable) {
        }
    }

    private fun addSceneLighting() {
        val viewer = modelViewer ?: return
        try {
            val sunlight = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.SUN)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(110_000.0f)
                .direction(0.3f, -1.0f, -0.6f)
                .castShadows(false)
                .build(viewer.engine, sunlight)
            viewer.scene.addEntity(sunlight)

            val fill = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(35_000.0f)
                .direction(-0.2f, -0.2f, 1.0f)
                .castShadows(false)
                .build(viewer.engine, fill)
            viewer.scene.addEntity(fill)
        } catch (e: Throwable) {
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
            faceModelTowardCamera()
            zoomInMore()
            diagnoseMorphTargets(assetPath)
        } catch (e: Throwable) {
            uiHandler.post {
                Toast.makeText(this, "Could not load model: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun faceModelTowardCamera() {
        val viewer = modelViewer ?: return
        try {
            val asset = viewer.asset ?: return
            val tm = viewer.engine.transformManager
            val rootEntity = asset.root
            val instance = tm.getInstance(rootEntity)
            if (instance == 0) return

            val current = FloatArray(16)
            tm.getTransform(instance, current)

            val rotation = FloatArray(16)
            Matrix.setIdentityM(rotation, 0)
            Matrix.rotateM(rotation, 0, 180f, 0f, 1f, 0f)

            val result = FloatArray(16)
            Matrix.multiplyMM(result, 0, current, 0, rotation, 0)
            tm.setTransform(instance, result)
        } catch (e: Throwable) {
        }
    }

    // Bigger / closer than before.
    private fun zoomInMore() {
        val viewer = modelViewer ?: return
        try {
            val camera: Camera = viewer.camera
            camera.lookAt(0.0, 0.0, 2.6, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        } catch (e: Throwable) {
        }
    }

    // DIAGNOSTIC STEP: instead of guessing another name, this shows on
    // screen exactly which morph target names this specific model actually
    // has, so the next fix targets the real name with certainty.
    private fun diagnoseMorphTargets(assetPath: String) {
        val viewer = modelViewer ?: return
        mouthTargets.clear()
        val foundNamesPerEntity = mutableListOf<String>()
        try {
            val asset = viewer.asset ?: return
            val rm = viewer.engine.renderableManager
            val candidates = listOf("mth_a", "mouth_a", "vrc.v_a", "v_a", "viseme_a", "mth", "mouth", "jaw", "_a", "aa")
            for (entity in asset.entities) {
                if (!rm.hasComponent(entity)) continue
                val names = try {
                    asset.getMorphTargetNames(entity)
                } catch (e: Exception) {
                    emptyArray()
                }
                if (names.isEmpty()) continue
                foundNamesPerEntity.add(names.joinToString(","))

                val instance = rm.getInstance(entity)
                val count = rm.getMorphTargetCount(instance)
                val lowerNames = names.map { it.lowercase() }
                for (candidate in candidates) {
                    val idx = lowerNames.indexOfFirst { it.contains(candidate) }
                    if (idx >= 0) {
                        mouthTargets.add(Triple(entity, idx, count))
                        break
                    }
                }
            }
        } catch (e: Throwable) {
        }

        uiHandler.post {
            val summary = if (foundNamesPerEntity.isEmpty()) {
                "No morph targets found at all in $assetPath"
            } else {
                "Matched mouth targets: ${mouthTargets.size}\nAll morph names:\n" +
                    foundNamesPerEntity.joinToString("\n").take(500)
            }
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
        }
    }

    private fun applyMouthWeight() {
        val viewer = modelViewer ?: return
        if (mouthTargets.isEmpty()) return
        try {
            val rm = viewer.engine.renderableManager
            val amount = liveAmplitude.coerceIn(0f, 1f)
            for ((entity, morphIndex, count) in mouthTargets) {
                if (morphIndex >= count) continue
                val instance = rm.getInstance(entity)
                val weights = FloatArray(count)
                weights[morphIndex] = amount
                rm.setMorphWeights(instance, weights, 0)
            }
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
