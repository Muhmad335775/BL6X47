package com.voicereact.app.page3

import android.content.res.AssetManager
import android.view.Surface
import com.google.android.filament.*
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class CinematicRenderer(
    private val outputWidth: Int = 1080,
    private val outputHeight: Int = 1920,
    private val fps: Int = 30
) {
    val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val camera: Camera
    private val cameraEntity = EntityManager.get().create()
    private lateinit var swapChain: SwapChain
    private lateinit var asset: FilamentAsset
    private lateinit var assetLoader: AssetLoader
    private lateinit var colorGrading: ColorGrading

    private var moodLight: Int = 0
    private val motionSeed = Random(System.nanoTime())

    private var baseCameraPosition = Triple(0f, 1.4f, 2.6f)
    private var bboxCenter = Triple(0f, 1.0f, 0f)
    private var visemeDriver: VisemeDriver? = null
    private val boneBaseTransforms = HashMap<String, FloatArray>()

    init {
        camera = engine.createCamera(cameraEntity)
        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
        view.camera = camera
        view.scene = scene
        view.isPostProcessingEnabled = true

        colorGrading = ColorGrading.Builder()
            .toneMapping(ColorGrading.ToneMapping.ACES_LEGACY)
            .contrast(1.12f)
            .saturation(1.05f)
            .build(engine)
        view.colorGrading = colorGrading
    }

    fun attachSurface(surface: Surface) {
        swapChain = engine.createSwapChain(surface)
        view.viewport = Viewport(0, 0, outputWidth, outputHeight)
    }

    fun setVisemeTimeline(frames: List<VisemeFrame>) {
        visemeDriver = VisemeDriver(frames)
    }

    fun loadCharacter(assetManager: AssetManager, characterNumber: Int, materialProvider: UbershaderProvider) {
        require(characterNumber in 1..15) { "رقم الشخصية لازم يكون بين 1 و15" }
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())

        val fixedPath = "$characterNumber" + "m/m.vrm"
        val bytes = assetManager.open(fixedPath).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        buffer.rewind()

        asset = assetLoader.createAsset(buffer) ?: error("فشل تحميل $fixedPath")

        val resourceLoader = ResourceLoader(engine)
        for (uri in asset.resourceUris) {
            val resBytes = assetManager.open(uri).use { it.readBytes() }
            val resBuffer = ByteBuffer.allocateDirect(resBytes.size).put(resBytes)
            resBuffer.rewind()
            resourceLoader.addResourceData(uri, resBuffer)
        }
        resourceLoader.loadResources(asset)
        resourceLoader.destroy()
        asset.releaseSourceData()

        scene.addEntities(asset.entities)

        setupMoodLighting()
        fitCameraToBoundingBox()
        captureBoneBaseline("Head")
        captureBoneBaseline("Hips")

        applyIdleMotion(0f)
        applyProceduralGesture(0f, false)
    }

    private fun fitCameraToBoundingBox() {
        val fit = BoundingBoxCameraFitter.compute(asset, outputWidth, outputHeight)
        baseCameraPosition = Triple(fit.eyeX, fit.eyeY, fit.eyeZ)
        bboxCenter = Triple(fit.centerX, fit.centerY, fit.centerZ)
        camera.setProjection(
            fit.verticalFovDegrees,
            outputWidth.toDouble() / outputHeight.toDouble(),
            0.05,
            100.0,
            Camera.Fov.VERTICAL
        )
    }

    fun setMoodLightColor(r: Float, g: Float, b: Float, intensityLux: Float = 60_000f) {
        if (moodLight != 0) engine.destroyEntity(moodLight)
        moodLight = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(r, g, b)
            .intensity(intensityLux)
            .direction(0.3f, -0.6f, -0.8f)
            .castShadows(true)
            .build(engine, moodLight)
        scene.addEntity(moodLight)

        val fill = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.POINT)
            .color(r, g, b)
            .intensity(intensityLux * 0.15f)
            .position(0f, 1.2f, 1.5f)
            .falloff(6f)
            .build(engine, fill)
        scene.addEntity(fill)
    }

    private fun setupMoodLighting() {
        setMoodLightColor(1.0f, 0.85f, 0.75f)
    }

    fun renderFrame(timeSeconds: Float, isSurpriseBeat: Boolean) {
        applyIdleMotion(timeSeconds)
        visemeDriver?.apply(timeSeconds) { name, weight -> setBlendShapeIfExists(name, weight) }
        applyProceduralGesture(timeSeconds, isSurpriseBeat)
        applyCameraDrift(timeSeconds)

        if (renderer.beginFrame(swapChain, System.nanoTime())) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun applyIdleMotion(t: Float) {
        val breathe = (sin(t * 1.4) * 0.015).toFloat()

        val animator = asset.instance.animator
        if (animator.animationCount > 0) {
            animator.applyAnimation(0, t % 6f)
            animator.updateBoneMatrices()
        }

        val blinkCycle = 4.5 + motionSeed.nextDouble(0.0, 1.5)
        val phase = (t % blinkCycle)
        if (phase in 0.0..0.12) {
            setBlendShapeIfExists("Blink", (breathe + 1f))
        } else {
            setBlendShapeIfExists("Blink", 0f)
        }
    }

    private fun applyProceduralGesture(t: Float, isSurpriseBeat: Boolean) {
        val headTilt = (sin(t * 0.6) * 4.0).toFloat()
        val weightShift = (sin(t * 0.35) * 0.02).toFloat()
        setBoneRotationIfExists("Head", headTilt)
        setBoneOffsetIfExists("Hips", weightShift)

        if (isSurpriseBeat) {
            setBoneRotationIfExists("Head", headTilt + 10f)
        }
    }

    private fun applyCameraDrift(t: Float) {
        val (bx, by, bz) = baseCameraPosition
        val (cx, cy, cz) = bboxCenter
        val driftX = (sin(t * 0.12) * 0.06).toFloat()
        val driftZ = (sin(t * 0.08) * 0.04).toFloat()
        camera.lookAt(
            (bx + driftX).toDouble(), by.toDouble(), (bz + driftZ).toDouble(),
            cx.toDouble(), cy.toDouble(), cz.toDouble(),
            0.0, 1.0, 0.0
        )
    }

    private fun setBlendShapeIfExists(name: String, weight: Float) {
        val rm = engine.renderableManager
        for (entity in asset.renderableEntities) {
            val ri = rm.getInstance(entity)
            if (ri == 0) continue
            val morphNames = asset.getMorphTargetNames(entity)
            val idx = morphNames.indexOf(name)
            if (idx >= 0) {
                val weights = FloatArray(morphNames.size)
                weights[idx] = weight.coerceIn(0f, 1f)
                rm.setMorphWeights(ri, weights, 0)
            }
        }
    }

    private fun captureBoneBaseline(boneName: String) {
        val entity = asset.getFirstEntityByName(boneName)
        if (entity == 0) return
        val tm = engine.transformManager
        val ti = tm.getInstance(entity)
        if (ti == 0) return
        val m = FloatArray(16)
        tm.getTransform(ti, m)
        boneBaseTransforms[boneName] = m
    }

    private fun setBoneRotationIfExists(boneName: String, degreesAroundX: Float) {
        val baseline = boneBaseTransforms[boneName] ?: return
        val entity = asset.getFirstEntityByName(boneName)
        if (entity == 0) return
        val tm = engine.transformManager
        val ti = tm.getInstance(entity)
        if (ti == 0) return
        val rotation = mat4RotationX(Math.toRadians(degreesAroundX.toDouble()).toFloat())
        tm.setTransform(ti, mat4Multiply(baseline, rotation))
    }

    private fun setBoneOffsetIfExists(boneName: String, offsetX: Float) {
        val baseline = boneBaseTransforms[boneName] ?: return
        val entity = asset.getFirstEntityByName(boneName)
        if (entity == 0) return
        val tm = engine.transformManager
        val ti = tm.getInstance(entity)
        if (ti == 0) return
        val translation = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            offsetX, 0f, 0f, 1f
        )
        tm.setTransform(ti, mat4Multiply(baseline, translation))
    }

    private fun mat4RotationX(radians: Float): FloatArray {
        val c = cos(radians)
        val s = sin(radians)
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, c, s, 0f,
            0f, -s, c, 0f,
            0f, 0f, 0f, 1f
        )
    }

    private fun mat4Multiply(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += a[k * 4 + row] * b[col * 4 + k]
                }
                result[col * 4 + row] = sum
            }
        }
        return result
    }

    fun destroy() {
        engine.destroyEntity(cameraEntity)
        if (moodLight != 0) engine.destroyEntity(moodLight)
        if (::asset.isInitialized) assetLoader.destroyAsset(asset)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        if (::swapChain.isInitialized) engine.destroySwapChain(swapChain)
        engine.destroy()
    }
}
