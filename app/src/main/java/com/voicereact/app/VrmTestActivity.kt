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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Choreographer
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
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
    private var blinkTargets: MutableList<Triple<Int, Int, Int>> = mutableListOf()
    private var reactionTargets: MutableList<Triple<Int, Int, Int>> = mutableListOf()

    private var rootEntity: Int = 0
    private var rootBaseTransform: FloatArray = FloatArray(16)
    private var hasRootTransform = false

    private var sunLightEntity: Int = 0
    private var fillLightEntity: Int = 0

    @Volatile private var liveAmplitude: Float = 0f
    @Volatile private var livePitchHz: Float = 150f
    @Volatile private var micRunning = false
    private var micThread: Thread? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val startTimeNanos = System.nanoTime()

    private var paletteA = doubleArrayOf(0.10, 0.09, 0.16)
    private var paletteB = doubleArrayOf(0.20, 0.10, 0.24)

    private val baseCameraDistance = 0.32

    private enum class Reaction { NONE, SHOCK, LAUGH }
    @Volatile private var currentReaction = Reaction.NONE
    @Volatile private var reactionStartSeconds = -1.0
    private val reactionDurationSeconds = 1.4

    private var speechRecognizer: SpeechRecognizer? = null
    // FIX: this used to restart itself forever on a timer, which fired an
    // audible tone every cycle — that's what looked like "the mic making
    // sounds on its own". Speech recognition now runs exactly ONE time per
    // screen, never restarts, never loops.
    private var speechHasRun = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer?.postFrameCallback(this)
            try {
                val elapsed = (frameTimeNanos - startTimeNanos) / 1_000_000_000.0
                updateAnimatedBackground(elapsed)
                updateMoodLighting()
                updateIdleMotion(elapsed)
                updateCameraDrift(elapsed)
                applyMouthWeight()
                applyBlink(elapsed)
                applyReaction(elapsed)
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
                startSpeechRecognitionOnce()
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

    private fun setPaletteForCharacter(number: Int) {
        val palettes = listOf(
            doubleArrayOf(0.12, 0.08, 0.20) to doubleArrayOf(0.30, 0.10, 0.28),
            doubleArrayOf(0.05, 0.12, 0.20) to doubleArrayOf(0.10, 0.28, 0.30),
            doubleArrayOf(0.20, 0.10, 0.06) to doubleArrayOf(0.32, 0.20, 0.08),
            doubleArrayOf(0.06, 0.16, 0.10) to doubleArrayOf(0.14, 0.30, 0.18),
            doubleArrayOf(0.16, 0.06, 0.16) to doubleArrayOf(0.30, 0.12, 0.22)
        )
        val (a, b) = palettes[number % palettes.size]
        paletteA = a
        paletteB = b
    }

    private fun updateAnimatedBackground(elapsedSeconds: Double) {
        val viewer = modelViewer ?: return
        try {
            val t = sin(elapsedSeconds * 0.5) * 0.5 + 0.5
            val r = paletteA[0] + (paletteB[0] - paletteA[0]) * t
            val g = paletteA[1] + (paletteB[1] - paletteA[1]) * t
            val b = paletteA[2] + (paletteB[2] - paletteA[2]) * t

            val renderer: Renderer = viewer.renderer
            val options = renderer.clearOptions
            options.clearColor = doubleArrayOf(r, g, b, 1.0)
            options.clear = true
            renderer.clearOptions = options
        } catch (e: Throwable) {
        }
    }

    private fun addSceneLighting() {
        val viewer = modelViewer ?: return
        try {
            sunLightEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.SUN)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(110_000.0f)
                .direction(0.3f, -1.0f, -0.6f)
                .castShadows(false)
                .build(viewer.engine, sunLightEntity)
            viewer.scene.addEntity(sunLightEntity)

            fillLightEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(35_000.0f)
                .direction(-0.2f, -0.2f, 1.0f)
                .castShadows(false)
                .build(viewer.engine, fillLightEntity)
            viewer.scene.addEntity(fillLightEntity)
        } catch (e: Throwable) {
        }
    }

    private fun updateMoodLighting() {
        val viewer = modelViewer ?: return
        try {
            val lm = viewer.engine.lightManager
            val pitch = livePitchHz.coerceIn(80f, 350f)
            val warmth = ((pitch - 80f) / (350f - 80f)).coerceIn(0f, 1f)

            val coolColor = floatArrayOf(0.55f, 0.65f, 1.0f)
            val warmColor = floatArrayOf(1.0f, 0.55f, 0.30f)
            val r = coolColor[0] + (warmColor[0] - coolColor[0]) * warmth
            val g = coolColor[1] + (warmColor[1] - coolColor[1]) * warmth
            val b = coolColor[2] + (warmColor[2] - coolColor[2]) * warmth

            val sunInstance = lm.getInstance(sunLightEntity)
            if (sunInstance != 0) lm.setColor(sunInstance, r, g, b)
            val fillInstance = lm.getInstance(fillLightEntity)
            if (fillInstance != 0) lm.setColor(fillInstance, r, g, b)
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
            startSpeechRecognitionOnce()
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
            captureRootTransform()
            fitFullScreenFraming()
            findFaceMorphTargets()
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
            val entity = asset.root
            val instance = tm.getInstance(entity)
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

    private fun captureRootTransform() {
        val viewer = modelViewer ?: return
        try {
            val asset = viewer.asset ?: return
            val tm = viewer.engine.transformManager
            rootEntity = asset.root
            val instance = tm.getInstance(rootEntity)
            if (instance == 0) return
            tm.getTransform(instance, rootBaseTransform)
            hasRootTransform = true
        } catch (e: Throwable) {
            hasRootTransform = false
        }
    }

    private fun fitFullScreenFraming() {
        val viewer = modelViewer ?: return
        try {
            val camera: Camera = viewer.camera
            camera.lookAt(0.0, 0.0, baseCameraDistance, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        } catch (e: Throwable) {
        }
    }

    private fun updateCameraDrift(elapsedSeconds: Double) {
        val viewer = modelViewer ?: return
        try {
            val camera: Camera = viewer.camera
            val zoomDrift = sin(elapsedSeconds * 0.18) * 0.02
            val panDrift = sin(elapsedSeconds * 0.12) * 0.01
            val eyeZ = baseCameraDistance + zoomDrift
            val eyeX = panDrift
            camera.lookAt(eyeX, 0.0, eyeZ, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        } catch (e: Throwable) {
        }
    }

    private fun updateIdleMotion(elapsedSeconds: Double) {
        val viewer = modelViewer ?: return
        if (!hasRootTransform) return
        try {
            val tm = viewer.engine.transformManager
            val instance = tm.getInstance(rootEntity)
            if (instance == 0) return

            var extraTilt = 0f
            if (currentReaction == Reaction.LAUGH && reactionStartSeconds >= 0) {
                val t = elapsedSeconds - reactionStartSeconds
                extraTilt = (sin(t * 18.0) * 6.0).toFloat()
            } else if (currentReaction == Reaction.SHOCK && reactionStartSeconds >= 0) {
                extraTilt = -4f
            }

            val breathe = 1.0f + (sin(elapsedSeconds * 1.1) * 0.012f).toFloat()
            val sway = (sin(elapsedSeconds * 0.6) * 1.5f).toFloat() + extraTilt

            val scaleM = FloatArray(16)
            Matrix.setIdentityM(scaleM, 0)
            Matrix.scaleM(scaleM, 0, 1.0f, breathe, 1.0f)

            val swayM = FloatArray(16)
            Matrix.setIdentityM(swayM, 0)
            Matrix.rotateM(swayM, 0, sway, 0f, 1f, 0f)

            val combined = FloatArray(16)
            Matrix.multiplyMM(combined, 0, rootBaseTransform, 0, swayM, 0)

            val finalM = FloatArray(16)
            Matrix.multiplyMM(finalM, 0, combined, 0, scaleM, 0)

            tm.setTransform(instance, finalM)
        } catch (e: Throwable) {
        }
    }

    private fun findFaceMorphTargets() {
        val viewer = modelViewer ?: return
        mouthTargets.clear()
        blinkTargets.clear()
        reactionTargets.clear()
        try {
            val asset = viewer.asset ?: return
            val rm = viewer.engine.renderableManager
            val mouthCandidates = listOf("mth_a", "mouth_a", "vrc.v_a", "v_a", "viseme_a", "mth", "mouth", "jaw", "_a", "aa")
            val blinkCandidates = listOf("blink", "eye_close", "eyeclose", "wink", "blk", "close")
            val reactionCandidates = listOf("surprised", "surprise", "fun", "joy", "blush", "shock")
            for (entity in asset.entities) {
                if (!rm.hasComponent(entity)) continue
                val names = try {
                    asset.getMorphTargetNames(entity)
                } catch (e: Exception) {
                    emptyArray()
                }
                if (names.isEmpty()) continue
                val instance = rm.getInstance(entity)
                val count = rm.getMorphTargetCount(instance)
                val lowerNames = names.map { it.lowercase() }

                for (candidate in mouthCandidates) {
                    val idx = lowerNames.indexOfFirst { it.contains(candidate) }
                    if (idx >= 0) { mouthTargets.add(Triple(entity, idx, count)); break }
                }
                for (candidate in blinkCandidates) {
                    val idx = lowerNames.indexOfFirst { it.contains(candidate) }
                    if (idx >= 0) { blinkTargets.add(Triple(entity, idx, count)); break }
                }
                for (candidate in reactionCandidates) {
                    val idx = lowerNames.indexOfFirst { it.contains(candidate) }
                    if (idx >= 0) { reactionTargets.add(Triple(entity, idx, count)); break }
                }
            }
        } catch (e: Throwable) {
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

    private fun applyBlink(elapsedSeconds: Double) {
        val viewer = modelViewer ?: return
        if (blinkTargets.isEmpty()) return
        try {
            val cyclePosition = elapsedSeconds % 4.0
            val closeAmount = if (cyclePosition in 3.80..3.95) {
                val phase = (cyclePosition - 3.80) / 0.15
                sin(phase * Math.PI).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
            val rm = viewer.engine.renderableManager
            for ((entity, morphIndex, count) in blinkTargets) {
                if (morphIndex >= count) continue
                val instance = rm.getInstance(entity)
                val weights = FloatArray(count)
                weights[morphIndex] = closeAmount
                rm.setMorphWeights(instance, weights, 0)
            }
        } catch (e: Throwable) {
        }
    }

    private fun applyReaction(elapsedSeconds: Double) {
        val viewer = modelViewer ?: return
        if (currentReaction == Reaction.NONE || reactionStartSeconds < 0) return

        val t = elapsedSeconds - reactionStartSeconds
        if (t > reactionDurationSeconds) {
            currentReaction = Reaction.NONE
            reactionStartSeconds = -1.0
            clearReactionWeights()
            return
        }

        if (reactionTargets.isEmpty()) return
        try {
            val rm = viewer.engine.renderableManager
            val envelope = sin((t / reactionDurationSeconds) * Math.PI).toFloat().coerceIn(0f, 1f)
            for ((entity, morphIndex, count) in reactionTargets) {
                if (morphIndex >= count) continue
                val instance = rm.getInstance(entity)
                val weights = FloatArray(count)
                weights[morphIndex] = envelope
                rm.setMorphWeights(instance, weights, 0)
            }
        } catch (e: Throwable) {
        }
    }

    private fun clearReactionWeights() {
        val viewer = modelViewer ?: return
        try {
            val rm = viewer.engine.renderableManager
            for ((entity, morphIndex, count) in reactionTargets) {
                if (morphIndex >= count) continue
                val instance = rm.getInstance(entity)
                val weights = FloatArray(count)
                rm.setMorphWeights(instance, weights, 0)
            }
        } catch (e: Throwable) {
        }
    }

    private fun triggerReaction(type: Reaction) {
        currentReaction = type
        reactionStartSeconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0
    }

    // Runs exactly once: starts listening, captures whatever is said in
    // that single window, then stops for good. No restart, no loop, no
    // repeated tones.
    private fun startSpeechRecognitionOnce() {
        if (speechHasRun) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechHasRun = true
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    // Single-shot: do nothing further, no restart.
                }
                override fun onResults(results: Bundle?) {
                    handleSpeechResult(results)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    handleSpeechResult(partialResults)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Throwable) {
        }
    }

    private fun handleSpeechResult(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val text = matches.joinToString(" ").lowercase()
        when {
            text.contains("wow") || text.contains("really") -> triggerReaction(Reaction.SHOCK)
            text.contains("haha") || text.contains("funny") -> triggerReaction(Reaction.LAUGH)
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

                        if (rms > 200.0) {
                            val pitch = estimatePitchHz(buffer, read, sampleRate)
                            if (pitch in 60f..500f) {
                                livePitchHz = livePitchHz * 0.7f + pitch * 0.3f
                            }
                        }
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

    private fun estimatePitchHz(buffer: ShortArray, length: Int, sampleRate: Int): Float {
        val minLag = sampleRate / 500
        val maxLag = sampleRate / 60
        if (maxLag >= length) return 0f

        var bestLag = -1
        var bestCorrelation = 0.0
        for (lag in minLag..maxLag) {
            var correlation = 0.0
            var i = 0
            while (i + lag < length) {
                correlation += buffer[i] * buffer[i + lag]
                i++
            }
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestLag = lag
            }
        }
        if (bestLag <= 0) return 0f
        return sampleRate.toFloat() / bestLag.toFloat()
    }

    private fun stopMicListening() {
        micRunning = false
        micThread = null
        try { speechRecognizer?.stopListening() } catch (e: Exception) {}
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
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
