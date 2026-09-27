package com.voicereact.app.page3

import android.content.res.AssetManager
import android.view.Surface
import com.google.android.filament.*
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import kotlin.math.sin
import kotlin.random.Random

class CinematicRenderer(
    private val outputWidth: Int = 1080,
    private val outputHeight: Int = 1920,
    private val fps: Int = 30,
    private val durationSeconds: Float
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
    private lateinit var resourceLoader: ResourceLoader

    private var moodLight: Int = 0
    private val motionSeed = Random(System.nanoTime())
    private var baseCameraPosition = Triple(0f, 1.45f, 2.6f)

    init {
        camera = engine.createCamera(cameraEntity)
        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
        view.camera = camera
        view.scene = scene
        view.isPostProcessingEnabled = true
    }

    fun attachSurface(surface: Surface) {
        swapChain = engine.createSwapChain(surface)
        view.viewport = Viewport(0, 0, outputWidth, outputHeight)
    }

    fun loadCharacter(assetManager: AssetManager, characterNumber: Int, materialProvider: UbershaderProvider) {
        require(characterNumber in 1..15) { "رقم الشخصية لازم يكون بين 1 و15" }
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        val fixedPath = "$characterNumber" + "m/m.vrm"
        val bytes = assetManager.open(fixedPath).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        buffer.rewind()

        asset = assetLoader.createAsset(buffer) ?: error("فشل تحميل $fixedPath")
        resourceLoader.asset = asset
        resourceLoader.loadResources()
        asset.releaseSourceData()
        scene.addEntities(asset.entities)

        setupMoodLighting()
        framePlayerInView()
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

    private fun framePlayerInView() {
        camera.setProjection(38.0, outputWidth.toDouble() / outputHeight.toDouble(), 0.1, 20.0, Camera.Fov.VERTICAL)
        baseCameraPosition = Triple(0f, 1.45f, 2.6f)
    }

    fun renderFrame(timeSeconds: Float, isSurpriseBeat: Boolean) {
        applyIdleMotion(timeSeconds)
        applyProceduralGesture(timeSeconds, isSurpriseBeat)
        applyCameraDrift(timeSeconds)

        if (renderer.beginFrame(swapChain, System.nanoTime())) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun applyIdleMotion(t: Float) {
        val breathe = (sin(t * 1.4) * 0.015).toFloat()
        asset.animator?.let { animator ->
            for (i in 0 until animator.animationCount) {
                val name = animator.getAnimationName(i)
                if (name.contains("idle", true) || name.contains("breath", true)) {
                    animator.applyAnimation(i, t % animator.getAnimationDuration(i))
                }
            }
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
        val driftX = (sin(t * 0.12) * 0.06).toFloat()
        val driftZ = (sin(t * 0.08) * 0.05).toFloat()
        val eye = doubleArrayOf((bx + driftX).toDouble(), by.toDouble(), (bz + driftZ).toDouble())
        camera.lookAt(eye[0], eye[1], eye[2], 0.0, 1.35, 0.0, 0.0, 1.0, 0.0)
    }

    private fun setBlendShapeIfExists(name: String, weight: Float) {
        asset.getEntitiesByName(name).firstOrNull()?.let { }
    }

    private fun setBoneRotationIfExists(boneName: String, degrees: Float) { }
    private fun setBoneOffsetIfExists(boneName: String, offset: Float) { }

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
