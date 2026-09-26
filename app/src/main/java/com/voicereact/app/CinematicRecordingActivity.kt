package com.voicereact.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.utils.Utils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class CinematicRecordingActivity : Activity() {

    companion object {
        const val EXTRA_AUDIO_PATH = "extra_audio_path"
        private const val TAG = "CinematicRecording"

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

    private lateinit var statusText: TextView
    private lateinit var rootLayout: FrameLayout
    private var videoView: android.widget.VideoView? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val maxRenderTimeMs = 90_000L
    @Volatile private var renderCompleted = false

    private val videoWidth = 720
    private val videoHeight = 1280
    private val videoFrameRate = 24
    private val videoBitrate = 6_000_000
    private val echoTailSeconds = 1.5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = FrameLayout(this)
        setContentView(rootLayout)

        statusText = TextView(this).apply {
            text = "Starting..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        rootLayout.addView(statusText, params)
        rootLayout.setBackgroundColor(Color.BLACK)

        val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
        if (audioPath == null || !File(audioPath).exists()) {
            Toast.makeText(this, "No recorded audio found", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!ensureFilamentInit()) {
            Toast.makeText(this, "3D engine failed to start on this device", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        watchdogHandler.postDelayed({
            if (!renderCompleted) {
                Toast.makeText(this, "Rendering is taking too long and was stopped. Please try again.", Toast.LENGTH_LONG).show()
                updateStatus("Timed out")
                goBackToRecorder()
            }
        }, maxRenderTimeMs)

        thread(start = true) {
            try {
                renderCinematicVideo(audioPath)
            } catch (e: Throwable) {
                Log.e(TAG, "Render pipeline failed", e)
                uiHandler.post {
                    Toast.makeText(this, "Render failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                goBackToRecorder()
            } finally {
                renderCompleted = true
            }
        }
    }

    private fun goBackToRecorder() {
        if (renderCompleted) return
        renderCompleted = true
        uiHandler.post {
            try {
                startActivity(Intent(this, MainActivity::class.java))
            } catch (e: Exception) {
            }
            finish()
        }
    }

    private fun renderCinematicVideo(audioPath: String) {
        updateStatus("Decoding audio...")
        val decodedAudio = decodePcm(audioPath)
        if (decodedAudio == null || decodedAudio.samples.isEmpty()) {
            uiHandler.post { Toast.makeText(this, "Could not decode audio — please record again", Toast.LENGTH_LONG).show() }
            goBackToRecorder()
            return
        }

        val speechDurationSeconds = decodedAudio.samples.size.toDouble() /
            (decodedAudio.sampleRate * decodedAudio.channelCount)
        val totalDurationSeconds = speechDurationSeconds + echoTailSeconds
        val totalFrames = (totalDurationSeconds * videoFrameRate).toInt().coerceAtLeast(1)

        updateStatus("Loading character...")
        val pickedNumber = Random.nextInt(1, 16)

        val engine = com.google.android.filament.Engine.create()
        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var swapChain: SwapChain? = null
        var renderer: Renderer? = null

        try {
            val camera = engine.createCamera(EntityManager.get().create())
            val scene = engine.createScene()
            val view = engine.createView()
            view.camera = camera
            view.scene = scene
            view.viewport = com.google.android.filament.Viewport(0, 0, videoWidth, videoHeight)

            applyColorGrading(engine, view)

            val sunEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.SUN)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(110_000.0f)
                .direction(0.3f, -1.0f, -0.6f)
                .castShadows(false)
                .build(engine, sunEntity)
            scene.addEntity(sunEntity)

            val fillEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 1.0f, 1.0f)
                .intensity(35_000.0f)
                .direction(-0.2f, -0.2f, 1.0f)
                .castShadows(false)
                .build(engine, fillEntity)
            scene.addEntity(fillEntity)

            updateStatus("Loading model...")
            val assetLoader = com.google.android.filament.gltfio.AssetLoader(
                engine,
                com.google.android.filament.gltfio.UbershaderProvider(engine),
                EntityManager.get()
            )
            val resourceLoader = com.google.android.filament.gltfio.ResourceLoader(engine)

            val modelBytes = assets.open("${pickedNumber}m/m.vrm").use { it.readBytes() }
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder()).put(modelBytes)
            modelBuffer.rewind()
            val asset = assetLoader.createAsset(modelBuffer) ?: throw IllegalStateException("Could not parse VRM model")
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)

            val tm = engine.transformManager
            val rootInstance = tm.getInstance(asset.root)
            val rootBaseTransform = FloatArray(16)
            if (rootInstance != 0) {
                tm.getTransform(rootInstance, rootBaseTransform)
                val rotation = FloatArray(16)
                Matrix.setIdentityM(rotation, 0)
                Matrix.rotateM(rotation, 0, 180f, 0f, 1f, 0f)
                val faced = FloatArray(16)
                Matrix.multiplyMM(faced, 0, rootBaseTransform, 0, rotation, 0)
                tm.setTransform(rootInstance, faced)
                tm.getTransform(rootInstance, rootBaseTransform)
            }

            camera.lookAt(0.0, 0.12, 0.60, 0.0, 0.12, 0.0, 0.0, 1.0, 0.0)

            val rm = engine.renderableManager
            val mouthTargets = mutableListOf<Triple<Int, Int, Int>>()
            val blinkTargets = mutableListOf<Triple<Int, Int, Int>>()
            val mouthCandidates = listOf("mth_a", "mouth_a", "vrc.v_a", "v_a", "viseme_a", "mth", "mouth", "jaw", "_a", "aa")
            val blinkCandidates = listOf("blink", "eye_close", "eyeclose", "wink", "blk", "close")
            for (entity in asset.entities) {
                if (!rm.hasComponent(entity)) continue
                val names = try { asset.getMorphTargetNames(entity) } catch (e: Exception) { emptyArray() }
                if (names.isEmpty()) continue
                val instance = rm.getInstance(entity)
                val count = rm.getMorphTargetCount(instance)
                val lowerNames = names.map { it.lowercase() }
                for (c in mouthCandidates) {
                    val idx = lowerNames.indexOfFirst { it.contains(c) }
                    if (idx >= 0) { mouthTargets.add(Triple(entity, idx, count)); break }
                }
                for (c in blinkCandidates) {
                    val idx = lowerNames.indexOfFirst { it.contains(c) }
                    if (idx >= 0) { blinkTargets.add(Triple(entity, idx, count)); break }
                }
            }

            val palettes = listOf(
                doubleArrayOf(0.12, 0.08, 0.20) to doubleArrayOf(0.30, 0.10, 0.28),
                doubleArrayOf(0.05, 0.12, 0.20) to doubleArrayOf(0.10, 0.28, 0.30),
                doubleArrayOf(0.20, 0.10, 0.06) to doubleArrayOf(0.32, 0.20, 0.08),
                doubleArrayOf(0.06, 0.16, 0.10) to doubleArrayOf(0.14, 0.30, 0.18),
                doubleArrayOf(0.16, 0.06, 0.16) to doubleArrayOf(0.30, 0.12, 0.22)
            )
            val (paletteA, paletteB) = palettes[pickedNumber % palettes.size]

            updateStatus("Preparing encoder...")
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, videoFrameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            swapChain = engine.createSwapChain(inputSurface)
            renderer = engine.createRenderer()

            val outputAudio = buildFullAudioWithEcho(decodedAudio, echoTailSeconds)
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, decodedAudio.sampleRate, decodedAudio.channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder.start()

            val videosDir = File(filesDir, "videos").apply { mkdirs() }
            val outputFile = File(videosDir, "cinematic_${System.currentTimeMillis()}.mp4")
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            fun tryStartMuxer() {
                if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                    muxer!!.start()
                    muxerStarted = true
                }
            }

            fun drainVideoEncoder(endOfStream: Boolean) {
                if (endOfStream) videoEncoder!!.signalEndOfInputStream()
                while (true) {
                    val outIndex = videoEncoder!!.dequeueOutputBuffer(bufferInfo, 10000)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            videoTrackIndex = muxer!!.addTrack(videoEncoder!!.outputFormat)
                            tryStartMuxer()
                        }
                        outIndex >= 0 -> {
                            val encodedData = videoEncoder!!.getOutputBuffer(outIndex)
                            if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                            videoEncoder!!.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                        }
                    }
                }
            }

            fun drainAudioEncoder(endOfStream: Boolean) {
                while (true) {
                    val outIndex = audioEncoder!!.dequeueOutputBuffer(bufferInfo, 10000)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            audioTrackIndex = muxer!!.addTrack(audioEncoder!!.outputFormat)
                            tryStartMuxer()
                        }
                        outIndex >= 0 -> {
                            val encodedData = audioEncoder!!.getOutputBuffer(outIndex)
                            if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                            }
                            audioEncoder!!.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                        }
                    }
                }
            }

            // FIX: keep a handle to this thread so we can WAIT for it to
            // truly finish before touching the muxer's final stop() call.
            // Before, only a fixed 300ms sleep guarded this, which was not
            // a real guarantee — on a slower device the audio thread was
            // still running when stop() was called, and the whole pipeline
            // hung forever at "Finalizing video...".
            val audioFeedThread = thread(start = true) {
                try {
                    feedPcmToEncoder(audioEncoder!!, outputAudio, decodedAudio.sampleRate, decodedAudio.channelCount)
                } catch (e: Throwable) {
                    Log.e(TAG, "Audio encode feed failed", e)
                }
            }

            val frameDurationUs = 1_000_000L / videoFrameRate
            var presentationTimeUs = 0L

            for (frameIndex in 0 until totalFrames) {
                updateStatus("Rendering frame ${frameIndex + 1} of $totalFrames...")
                val elapsedSeconds = frameIndex.toDouble() / videoFrameRate

                val amplitude = amplitudeAt(decodedAudio, elapsedSeconds)
                val pitch = pitchAt(decodedAudio, elapsedSeconds)

                applyMouth(rm, mouthTargets, amplitude)
                applyBlink(rm, blinkTargets, elapsedSeconds)
                applyIdleMotion(tm, rootInstance, rootBaseTransform, elapsedSeconds)
                applyMoodLighting(engine.lightManager, sunEntity, fillEntity, pitch)
                applyBackgroundColor(renderer!!, paletteA, paletteB, elapsedSeconds)
                applyCameraDrift(camera, elapsedSeconds)

                renderer!!.beginFrame(swapChain!!, presentationTimeUs * 1000L)
                renderer!!.render(view)
                renderer!!.endFrame()

                presentationTimeUs += frameDurationUs
                drainVideoEncoder(false)
            }

            updateStatus("Waiting for audio to finish encoding...")
            // Real wait, bounded so a stuck thread can't hang forever either.
            audioFeedThread.join(15000)

            updateStatus("Finalizing video...")
            drainVideoEncoder(true)
            drainAudioEncoder(true)

            try { muxer.stop() } catch (e: Exception) {
                Log.e(TAG, "muxer.stop failed", e)
            }

            updateStatus("Done")
            uiHandler.post { playFinishedVideo(outputFile) }

        } catch (e: Throwable) {
            Log.e(TAG, "renderCinematicVideo failed", e)
            uiHandler.post {
                Toast.makeText(this, "Video render failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            goBackToRecorder()
        } finally {
            try { videoEncoder?.stop() } catch (e: Exception) {}
            try { videoEncoder?.release() } catch (e: Exception) {}
            try { audioEncoder?.stop() } catch (e: Exception) {}
            try { audioEncoder?.release() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
            try { swapChain?.let { engine.destroySwapChain(it) } } catch (e: Exception) {}
            try { renderer?.let { engine.destroyRenderer(it) } } catch (e: Exception) {}
            try { engine.destroy() } catch (e: Exception) {}
        }
    }

    private fun applyMouth(rm: com.google.android.filament.RenderableManager, targets: List<Triple<Int, Int, Int>>, amount: Float) {
        try {
            for ((entity, morphIndex, count) in targets) {
                if (morphIndex >= count) continue
                val instance = rm.getInstance(entity)
                val weights = FloatArray(count)
                weights[morphIndex] = amount.coerceIn(0f, 1f)
                rm.setMorphWeights(instance, weights, 0)
            }
        } catch (e: Throwable) {
        }
    }

    private fun applyBlink(rm: com.google.android.filament.RenderableManager, targets: List<Triple<Int, Int, Int>>, elapsedSeconds: Double) {
        try {
            val cyclePosition = elapsedSeconds % 4.0
            val closeAmount = if (cyclePosition in 3.80..3.95) {
                val phase = (cyclePosition - 3.80) / 0.15
                sin(phase * Math.PI).toFloat().coerceIn(0f, 1f)
            } else 0f
            for ((entity, morphIndex, count) in targets) {
                if (morphIndex >= count) continue
                val instance = rm.getInstance(entity)
                val weights = FloatArray(count)
                weights[morphIndex] = closeAmount
                rm.setMorphWeights(instance, weights, 0)
            }
        } catch (e: Throwable) {
        }
    }

    private fun applyIdleMotion(
        tm: com.google.android.filament.TransformManager,
        rootInstance: Int,
        rootBaseTransform: FloatArray,
        elapsedSeconds: Double
    ) {
        if (rootInstance == 0) return
        try {
            val breathe = 1.0f + (sin(elapsedSeconds * 1.1) * 0.012f).toFloat()
            val sway = (sin(elapsedSeconds * 0.6) * 1.5f).toFloat()

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
            tm.setTransform(rootInstance, finalM)
        } catch (e: Throwable) {
        }
    }

    private fun applyMoodLighting(lm: com.google.android.filament.LightManager, sunEntity: Int, fillEntity: Int, pitchHz: Float) {
        try {
            val pitch = pitchHz.coerceIn(80f, 350f)
            val warmth = ((pitch - 80f) / (350f - 80f)).coerceIn(0f, 1f)
            val coolColor = floatArrayOf(0.55f, 0.65f, 1.0f)
            val warmColor = floatArrayOf(1.0f, 0.55f, 0.30f)
            val r = coolColor[0] + (warmColor[0] - coolColor[0]) * warmth
            val g = coolColor[1] + (warmColor[1] - coolColor[1]) * warmth
            val b = coolColor[2] + (warmColor[2] - coolColor[2]) * warmth
            val sunInstance = lm.getInstance(sunEntity)
            if (sunInstance != 0) lm.setColor(sunInstance, r, g, b)
            val fillInstance = lm.getInstance(fillEntity)
            if (fillInstance != 0) lm.setColor(fillInstance, r, g, b)
        } catch (e: Throwable) {
        }
    }

    private fun applyBackgroundColor(renderer: Renderer, paletteA: DoubleArray, paletteB: DoubleArray, elapsedSeconds: Double) {
        try {
            val t = sin(elapsedSeconds * 0.5) * 0.5 + 0.5
            val r = paletteA[0] + (paletteB[0] - paletteA[0]) * t
            val g = paletteA[1] + (paletteB[1] - paletteA[1]) * t
            val b = paletteA[2] + (paletteB[2] - paletteA[2]) * t
            val options = renderer.clearOptions
            options.clearColor = doubleArrayOf(r, g, b, 1.0)
            options.clear = true
            renderer.clearOptions = options
        } catch (e: Throwable) {
        }
    }

    private fun applyCameraDrift(camera: Camera, elapsedSeconds: Double) {
        try {
            val zoomDrift = sin(elapsedSeconds * 0.18) * 0.02
            val panDrift = sin(elapsedSeconds * 0.12) * 0.01
            camera.lookAt(panDrift, 0.12, 0.60 + zoomDrift, 0.0, 0.12, 0.0, 0.0, 1.0, 0.0)
        } catch (e: Throwable) {
        }
    }

    private fun applyColorGrading(engine: com.google.android.filament.Engine, view: View) {
        try {
            val grading = ColorGrading.Builder()
                .toneMapping(ColorGrading.ToneMapping.ACES)
                .contrast(1.08f)
                .saturation(1.12f)
                .build(engine)
            view.colorGrading = grading
            view.vignetteOptions = View.VignetteOptions().apply {
                enabled = true
                midPoint = 0.5f
                roundness = 0.6f
                feather = 0.55f
                color = floatArrayOf(0f, 0f, 0f, 1f)
            }
        } catch (e: Throwable) {
        }
    }

    private data class DecodedAudio(val samples: ShortArray, val sampleRate: Int, val channelCount: Int)

    private fun decodePcm(path: String): DecodedAudio? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val output = mutableListOf<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIndex >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val chunk = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(chunk)
                        for (s in chunk) output.add(s)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
            decoder.stop()
            decoder.release()
            return DecodedAudio(output.toShortArray(), sampleRate, channelCount)
        } catch (e: Throwable) {
            Log.e(TAG, "decodePcm failed", e)
            return null
        } finally {
            try { extractor.release() } catch (e: Exception) {}
        }
    }

    private fun amplitudeAt(audio: DecodedAudio, elapsedSeconds: Double): Float {
        val windowSamples = (audio.sampleRate * 0.05).toInt().coerceAtLeast(1) * audio.channelCount
        val centerIndex = (elapsedSeconds * audio.sampleRate * audio.channelCount).toInt()
        if (centerIndex >= audio.samples.size) return 0f
        val start = (centerIndex - windowSamples / 2).coerceIn(0, audio.samples.size - 1)
        val end = (start + windowSamples).coerceAtMost(audio.samples.size)
        if (end <= start) return 0f
        var sum = 0.0
        for (i in start until end) sum += (audio.samples[i].toDouble() * audio.samples[i].toDouble())
        val rms = sqrt(sum / (end - start))
        return (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun pitchAt(audio: DecodedAudio, elapsedSeconds: Double): Float {
        val windowSamples = (audio.sampleRate * 0.05).toInt().coerceAtLeast(1) * audio.channelCount
        val centerIndex = (elapsedSeconds * audio.sampleRate * audio.channelCount).toInt()
        if (centerIndex >= audio.samples.size) return 150f
        val start = (centerIndex - windowSamples / 2).coerceIn(0, audio.samples.size - 1)
        val end = (start + windowSamples).coerceAtMost(audio.samples.size)
        if (end - start < 64) return 150f
        val window = audio.samples.copyOfRange(start, end)
        val pitch = estimatePitchHz(window, audio.sampleRate)
        return if (pitch in 60f..500f) pitch else 150f
    }

    private fun estimatePitchHz(buffer: ShortArray, sampleRate: Int): Float {
        val minLag = sampleRate / 500
        val maxLag = sampleRate / 60
        if (maxLag >= buffer.size) return 0f
        var bestLag = -1
        var bestCorrelation = 0.0
        for (lag in minLag..maxLag) {
            var correlation = 0.0
            var i = 0
            while (i + lag < buffer.size) {
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

    private fun buildFullAudioWithEcho(audio: DecodedAudio, tailSeconds: Double): ShortArray {
        val tailWindowSamples = (audio.sampleRate * min(tailSeconds, 2.0)).toInt() * audio.channelCount
        val start = (audio.samples.size - tailWindowSamples).coerceAtLeast(0)
        val tailWindow = audio.samples.copyOfRange(start, audio.samples.size)
        val dominantPitch = if (tailWindow.size > 64) {
            estimatePitchHz(tailWindow, audio.sampleRate).takeIf { it in 60f..500f } ?: 220f
        } else 220f

        val echoSampleCount = (audio.sampleRate * tailSeconds).toInt() * audio.channelCount
        val echo = ShortArray(echoSampleCount)
        val framesTotal = echoSampleCount / audio.channelCount
        for (frame in 0 until framesTotal) {
            val t = frame.toDouble() / audio.sampleRate
            val envelope = (1.0 - (frame.toDouble() / framesTotal)).coerceIn(0.0, 1.0)
            val value = (sin(2.0 * Math.PI * dominantPitch * t) * envelope * 12000.0).toInt()
                .coerceIn(-32768, 32767).toShort()
            for (ch in 0 until audio.channelCount) {
                echo[frame * audio.channelCount + ch] = value
            }
        }

        return audio.samples + echo
    }

    private fun feedPcmToEncoder(encoder: MediaCodec, pcm: ShortArray, sampleRate: Int, channelCount: Int) {
        val byteData = ByteArray(pcm.size * 2)
        val bb = ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bb.putShort(s)

        var offset = 0
        val chunkSize = 4096
        val bytesPerSecond = sampleRate * channelCount * 2
        var presentationTimeUs = 0L

        while (offset < byteData.size) {
            val inIndex = encoder.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inIndex)!!
                inputBuffer.clear()
                val size = min(chunkSize, byteData.size - offset)
                inputBuffer.put(byteData, offset, size)
                encoder.queueInputBuffer(inIndex, 0, size, presentationTimeUs, 0)
                offset += size
                presentationTimeUs = (offset.toLong() * 1_000_000L) / bytesPerSecond
            }
        }
        val inIndex = encoder.dequeueInputBuffer(10000)
        if (inIndex >= 0) {
            encoder.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    private fun playFinishedVideo(file: File) {
        renderCompleted = true
        try {
            rootLayout.removeAllViews()
            val vv = android.widget.VideoView(this)
            videoView = vv
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            rootLayout.addView(vv, params)
            vv.setVideoPath(file.absolutePath)
            vv.setOnPreparedListener { mp -> mp.isLooping = true }
            vv.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Could not play finished video", Toast.LENGTH_LONG).show()
                true
            }
            vv.start()
        } catch (e: Throwable) {
            Toast.makeText(this, "Playback error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus(text: String) {
        uiHandler.post {
            try { statusText.text = text } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderCompleted = true
        watchdogHandler.removeCallbacksAndMessages(null)
        try { videoView?.stopPlayback() } catch (e: Exception) {}
    }
}
