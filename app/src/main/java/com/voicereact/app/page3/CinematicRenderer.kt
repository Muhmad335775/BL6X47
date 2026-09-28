package com.voicereact.app.page3

import android.content.res.AssetManager
import android.view.Surface
import com.google.android.filament.*
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private val TAU: Float = (2.0 * PI).toFloat()
private const val ARM_DOWN_DEGREES = 70f

private object Mat4 {
    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
                r[col * 4 + row] = s
            }
        }
        return r
    }

    fun rotX(rad: Float): FloatArray {
        val c = cos(rad)
        val s = sin(rad)
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, c, s, 0f,
            0f, -s, c, 0f,
            0f, 0f, 0f, 1f
        )
    }

    fun rotY(rad: Float): FloatArray {
        val c = cos(rad)
        val s = sin(rad)
        return floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            0f, 0f, 0f, 1f
        )
    }

    fun rotZ(rad: Float): FloatArray {
        val c = cos(rad)
        val s = sin(rad)
        return floatArrayOf(
            c, s, 0f, 0f,
            -s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    fun translation(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        x, y, z, 1f
    )

    fun inverse(m: FloatArray): FloatArray {
        val a = Array(4) { r ->
            DoubleArray(8) { c ->
                if (c < 4) m[r * 4 + c].toDouble() else if (c - 4 == r) 1.0 else 0.0
            }
        }
        for (col in 0 until 4) {
            var pivot = col
            for (r in col + 1 until 4) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r
            }
            if (Math.abs(a[pivot][col]) < 1e-12) return identity()
            val tmp = a[col]
            a[col] = a[pivot]
            a[pivot] = tmp
            val pv = a[col][col]
            for (c in 0 until 8) a[col][c] /= pv
            for (r in 0 until 4) {
                if (r != col) {
                    val f = a[r][col]
                    if (f != 0.0) {
                        for (c in 0 until 8) a[r][c] -= f * a[col][c]
                    }
                }
            }
        }
        return FloatArray(16) { i -> a[i / 4][4 + i % 4].toFloat() }
    }
}

class CinematicRenderer(
    private val outputWidth: Int,
    private val outputHeight: Int
) {
    private class Bone(
        val ti: Int,
        var local: FloatArray,
        var world: FloatArray,
        var invWorld: FloatArray
    )

    private class MorphTarget(val entity: Int, val index: Int, val scale: Float)

    val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity: Int = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val colorGrading: ColorGrading = ColorGrading.Builder()
        .toneMapping(ColorGrading.ToneMapping.ACES_LEGACY)
        .contrast(1.12f)
        .saturation(1.05f)
        .build(engine)

    private var swapChain: SwapChain? = null
    private var asset: FilamentAsset? = null
    private var assetLoader: AssetLoader? = null
    private var materialProvider: UbershaderProvider? = null
    private var skybox: Skybox? = null
    private var keyLight: Int = 0
    private var fillLight: Int = 0
    private var destroyed = false

    private val bones = HashMap<String, Bone>()
    private val armLowerSign = HashMap<String, Float>()
    private val expressionTargets = HashMap<String, List<MorphTarget>>()
    private val weightArrays = HashMap<Int, FloatArray>()
    private val tmpMatrix = FloatArray(16)

    private var facingSign = 1f
    private var baseEye = floatArrayOf(0f, 1.4f, 2.6f)
    private var lookAtCenter = floatArrayOf(0f, 1f, 0f)

    private var envelope = FloatArray(0)
    private var envelopeWindow = 0.03f
    private var mouthSmooth = 0f
    private var energySmooth = 0f
    private var moodColor = floatArrayOf(1.0f, 0.85f, 0.75f)

    private val probeMin = FloatArray(6) { Float.MAX_VALUE }
    private val probeMax = FloatArray(6) { -Float.MAX_VALUE }

    var armDropMeters: Float = 0f
        private set

    val foundBoneCount: Int
        get() = bones.size

    val bodyMotionMeters: Float
        get() {
            var best = 0f
            for (i in 0 until 6) {
                val range = probeMax[i] - probeMin[i]
                if (range > best && range < 1e6f) best = range
            }
            return best
        }

    init {
        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
        view.camera = camera
        view.scene = scene
        view.isPostProcessingEnabled = true
        view.colorGrading = colorGrading
    }

    fun attachSurface(surface: Surface) {
        swapChain = engine.createSwapChain(surface)
        view.viewport = Viewport(0, 0, outputWidth, outputHeight)
    }

    fun setAudioEnvelope(values: FloatArray, windowSeconds: Float) {
        envelope = values
        envelopeWindow = windowSeconds
    }

    fun setMoodLightColor(r: Float, g: Float, b: Float) {
        moodColor = floatArrayOf(r, g, b)
        rebuildLights()
    }

    fun loadCharacter(assetManager: AssetManager, characterNumber: Int) {
        require(characterNumber in 1..15) { "رقم الشخصية لازم يكون بين 1 و15" }
        val fixedPath = "${characterNumber}m/m.vrm"
        val bytes = assetManager.open(fixedPath).use { it.readBytes() }
        val info = VrmHumanoidBoneResolver.resolve(bytes)

        val provider = UbershaderProvider(engine)
        materialProvider = provider
        val loader = AssetLoader(engine, provider, EntityManager.get())
        assetLoader = loader

        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.rewind()
        val loaded = loader.createAsset(buffer) ?: error("فشل تحميل $fixedPath")
        asset = loaded

        val resourceLoader = ResourceLoader(engine)
        for (uri in loaded.resourceUris) {
            if (uri.startsWith("data:")) continue
            try {
                val resBytes = assetManager.open(uri).use { it.readBytes() }
                val resBuffer = ByteBuffer.allocateDirect(resBytes.size)
                resBuffer.put(resBytes)
                resBuffer.rewind()
                resourceLoader.addResourceData(uri, resBuffer)
            } catch (ignored: java.io.IOException) {
            }
        }
        resourceLoader.loadResources(loaded)
        resourceLoader.destroy()

        scene.addEntities(loaded.entities)

        captureBones(info.bones)
        determineFacing(info)
        lowerArms()
        buildMorphCache(info)
        fitCamera()
        rebuildLights()

        loaded.releaseSourceData()
    }

    private fun captureBones(names: HumanoidBoneNames) {
        captureBone("hips", names.hips)
        captureBone("spine", names.spine)
        captureBone("chest", names.chest)
        captureBone("neck", names.neck)
        captureBone("head", names.head)
        captureBone("leftUpperArm", names.leftUpperArm)
        captureBone("leftLowerArm", names.leftLowerArm)
        captureBone("rightUpperArm", names.rightUpperArm)
        captureBone("rightLowerArm", names.rightLowerArm)
    }

    private fun captureBone(role: String, nodeName: String) {
        if (nodeName.isEmpty()) return
        val a = asset ?: return
        val entity = a.getFirstEntityByName(nodeName)
        if (entity == 0) return
        val ti = engine.transformManager.getInstance(entity)
        if (ti == 0) return
        val bone = Bone(ti, FloatArray(16), FloatArray(16), FloatArray(16))
        refreshBone(bone)
        bones[role] = bone
    }

    private fun refreshBone(b: Bone) {
        val tm = engine.transformManager
        tm.getTransform(b.ti, b.local)
        tm.getWorldTransform(b.ti, b.world)
        b.invWorld = Mat4.inverse(b.world)
    }

    private fun determineFacing(info: VrmInfo) {
        val left = bones["leftUpperArm"]
        val right = bones["rightUpperArm"]
        facingSign = if (left != null && right != null) {
            if (left.world[12] > right.world[12]) 1f else -1f
        } else if (info.isVrm0) {
            -1f
        } else {
            1f
        }
    }

    private fun deg(d: Float): Float = d * (PI.toFloat() / 180f)

    /** يدوّر/يزيح العظمة بمحاور العالم حول موقعها هي، مقارنة بوضعها الأساسي (baseline) */
    private fun poseBone(
        role: String,
        rx: Float, ry: Float, rz: Float,
        tx: Float = 0f, ty: Float = 0f, tz: Float = 0f
    ) {
        val b = bones[role] ?: return
        val ox = b.world[12]
        val oy = b.world[13]
        val oz = b.world[14]
        val rot = Mat4.mul(Mat4.rotZ(rz), Mat4.mul(Mat4.rotX(rx), Mat4.rotY(ry)))
        val op = Mat4.mul(
            Mat4.translation(tx + ox, ty + oy, tz + oz),
            Mat4.mul(rot, Mat4.translation(-ox, -oy, -oz))
        )
        val m = Mat4.mul(b.invWorld, Mat4.mul(op, b.world))
        engine.transformManager.setTransform(b.ti, Mat4.mul(b.local, m))
    }

    private fun lowerArms() {
        val hips = bones["hips"]
        val leftUpper = bones["leftUpperArm"]
        val rightUpper = bones["rightUpperArm"]
        val centerX = when {
            hips != null -> hips.world[12]
            leftUpper != null && rightUpper != null -> (leftUpper.world[12] + rightUpper.world[12]) / 2f
            else -> 0f
        }
        val leftDrop = lowerOneArm("leftUpperArm", "leftLowerArm", centerX)
        val rightDrop = lowerOneArm("rightUpperArm", "rightLowerArm", centerX)
        for (b in bones.values) refreshBone(b)
        armDropMeters = max(leftDrop, rightDrop)
    }

    private fun lowerOneArm(upperRole: String, lowerRole: String, centerX: Float): Float {
        val upper = bones[upperRole] ?: return 0f
        val lower = bones[lowerRole]
        val side = if (upper.world[12] > centerX) 1f else -1f
        val tm = engine.transformManager
        var bestSign = -side
        var bestDrop = -1e9f
        for (sign in floatArrayOf(-side, side)) {
            poseBone(upperRole, 0f, 0f, sign * deg(ARM_DOWN_DEGREES))
            val drop = if (lower != null) {
                tm.getWorldTransform(lower.ti, tmpMatrix)
                lower.world[13] - tmpMatrix[13]
            } else {
                1f
            }
            if (drop > bestDrop) {
                bestDrop = drop
                bestSign = sign
            }
        }
        poseBone(upperRole, 0f, 0f, bestSign * deg(ARM_DOWN_DEGREES))
        armLowerSign[upperRole] = bestSign
        return bestDrop
    }

    private fun buildMorphCache(info: VrmInfo) {
        val a = asset ?: return
        val rm = engine.renderableManager
        val nameIndex = HashMap<String, MutableList<Pair<Int, Int>>>()
        for (entity in a.renderableEntities) {
            if (rm.getInstance(entity) == 0) continue
            val names = a.getMorphTargetNames(entity)
            if (names.isEmpty()) continue
            weightArrays[entity] = FloatArray(names.size)
            for (i in names.indices) {
                nameIndex.getOrPut(names[i]) { mutableListOf() }.add(Pair(entity, i))
            }
        }

        fun resolve(role: String, fallbacks: List<String>) {
            val list = ArrayList<MorphTarget>()
            val bindings = info.expressions[role].orEmpty()
            for (binding in bindings) {
                nameIndex[binding.targetName]?.forEach { list.add(MorphTarget(it.first, it.second, binding.scale)) }
            }
            if (list.isEmpty()) {
                for (name in fallbacks) {
                    nameIndex[name]?.forEach { list.add(MorphTarget(it.first, it.second, 1f)) }
                }
            }
            expressionTargets[role] = list
        }

        resolve("A", listOf("Fcl_MTH_A", "A", "aa", "vrc.v_aa"))
        resolve("I", listOf("Fcl_MTH_I", "I", "ih", "vrc.v_ih"))
        resolve("U", listOf("Fcl_MTH_U", "U", "ou", "vrc.v_ou"))
        resolve("E", listOf("Fcl_MTH_E", "E", "ee", "vrc.v_e"))
        resolve("O", listOf("Fcl_MTH_O", "O", "oh", "vrc.v_oh"))
        resolve("BLINK", listOf("Fcl_EYE_Close", "Blink", "blink", "Fcl_EYE_Close_R", "Fcl_EYE_Close_L"))
        resolve("SURPRISED", listOf("Fcl_EYE_Surprised", "Fcl_ALL_Surprised"))
    }

    private fun fitCamera() {
        val a = asset ?: return
        val fit = BoundingBoxCameraFitter.compute(a, outputWidth, outputHeight, facingSign)
        baseEye = floatArrayOf(fit.eyeX, fit.eyeY, fit.eyeZ)
        lookAtCenter = floatArrayOf(fit.centerX, fit.centerY, fit.centerZ)
        camera.setProjection(
            fit.verticalFovDegrees,
            outputWidth.toDouble() / outputHeight.toDouble(),
            0.05,
            100.0,
            Camera.Fov.VERTICAL
        )
    }

    private fun removeLight(entity: Int) {
        if (entity != 0) {
            scene.removeEntity(entity)
            engine.destroyEntity(entity)
        }
    }

    private fun rebuildLights() {
        removeLight(keyLight)
        removeLight(fillLight)
        val f = facingSign

        keyLight = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(moodColor[0], moodColor[1], moodColor[2])
            .intensity(55_000f)
            .direction(0.35f, -0.55f, -f * 0.8f)
            .castShadows(false)
            .build(engine, keyLight)
        scene.addEntity(keyLight)

        fillLight = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(20_000f)
            .direction(-0.5f, -0.2f, -f * 0.6f)
            .castShadows(false)
            .build(engine, fillLight)
        scene.addEntity(fillLight)

        val newSkybox = Skybox.Builder()
            .color(moodColor[0] * 0.22f, moodColor[1] * 0.22f, moodColor[2] * 0.22f, 1.0f)
            .build(engine)
        scene.skybox = newSkybox
        skybox?.let { engine.destroySkybox(it) }
        skybox = newSkybox
    }

    fun renderFrame(t: Float, voiceSeconds: Float, surpriseAt: Float?, idle: () -> Unit) {
        val surprise = if (surpriseAt != null) {
            val d = (t - surpriseAt) / 0.3f
            exp(-d * d)
        } else {
            0f
        }

        applyFace(t, voiceSeconds, surprise)
        applyBodyMotion(t, surprise)
        asset?.instance?.animator?.updateBoneMatrices()
        trackProbes()
        applyCameraDrift(t)

        val sc = swapChain ?: throw IllegalStateException("لا يوجد SwapChain")
        var attempts = 0
        while (!renderer.beginFrame(sc, System.nanoTime())) {
            idle()
            Thread.sleep(2)
            attempts++
            if (attempts > 1500) throw IllegalStateException("beginFrame timeout — الـGPU متوقف")
        }
        renderer.render(view)
        renderer.endFrame()
    }

    private fun clearMorphs() {
        for (arr in weightArrays.values) java.util.Arrays.fill(arr, 0f)
    }

    private fun setExpression(role: String, weight: Float) {
        val targets = expressionTargets[role] ?: return
        for (t in targets) {
            val arr = weightArrays[t.entity] ?: continue
            val v = (weight * t.scale).coerceIn(0f, 1f)
            if (v > arr[t.index]) arr[t.index] = v
        }
    }

    private fun commitMorphs() {
        val rm = engine.renderableManager
        for ((entity, arr) in weightArrays) {
            val ri = rm.getInstance(entity)
            if (ri != 0) rm.setMorphWeights(ri, arr, 0)
        }
    }

    private fun applyFace(t: Float, voiceSeconds: Float, surprise: Float) {
        clearMorphs()

        var amp = if (t <= voiceSeconds) AudioEnvelope.sample(envelope, t, envelopeWindow) else 0f
        amp = sqrt(amp.coerceIn(0f, 1f))
        mouthSmooth += (amp - mouthSmooth) * (if (amp > mouthSmooth) 0.65f else 0.35f)
        energySmooth += (amp - energySmooth) * 0.12f

        val m = mouthSmooth.coerceIn(0f, 1f)
        setExpression("A", min(0.95f, m * 1.5f))
        setExpression("I", 0.25f * m * (0.5f + 0.5f * sin(t * 7.1f)))
        setExpression("O", 0.35f * m * (0.5f + 0.5f * sin(t * 5.3f + 1f)))
        setExpression("U", 0.15f * m * (0.5f + 0.5f * sin(t * 4.1f + 2f)))
        setExpression("E", 0.20f * m * (0.5f + 0.5f * sin(t * 6.2f + 3f)))

        val cycle = t % 3.9f
        val blink = if (cycle < 0.14f) sin(cycle / 0.14f * PI.toFloat()) else 0f
        setExpression("BLINK", blink)
        setExpression("SURPRISED", surprise * 0.9f)

        commitMorphs()
    }

    private fun applyBodyMotion(t: Float, surprise: Float) {
        val e = energySmooth.coerceIn(0f, 1f)
        val f = facingSign

        poseBone(
            "hips",
            0f, deg(5f) * sin(t * 0.35f + 1f), deg(2.5f) * sin(t * 0.6f),
            0.02f * sin(t * 0.6f), 0.004f * sin(t * TAU * 0.27f), 0f
        )
        poseBone(
            "spine",
            f * deg(1.5f + 3f * e * (0.5f + 0.5f * sin(t * TAU * 1.1f))),
            0f,
            deg(1.5f) * sin(t * 0.6f + 0.5f)
        )
        poseBone(
            "chest",
            f * deg(1.2f) * sin(t * TAU * 0.27f),
            0f,
            deg(1.2f) * sin(t * 0.8f)
        )
        poseBone(
            "neck",
            f * deg(1f + 2f * e * sin(t * TAU * 1.6f)),
            deg(4f) * sin(t * 0.5f + 2f),
            0f
        )
        poseBone(
            "head",
            f * deg(1.5f + (2f + 5f * e) * sin(t * TAU * 1.7f)) - f * deg(12f) * surprise,
            deg(7f) * sin(t * 0.42f + 0.7f),
            deg(4f) * sin(t * 0.6f)
        )

        animateArm("leftUpperArm", "leftLowerArm", t, e, surprise, 0.0f, 1.10f)
        animateArm("rightUpperArm", "rightLowerArm", t, e, surprise, 1.7f, 1.30f)
    }

    private fun animateArm(
        upperRole: String,
        lowerRole: String,
        t: Float,
        e: Float,
        surprise: Float,
        phase: Float,
        freq: Float
    ) {
        val lowerSign = armLowerSign[upperRole] ?: return
        val liftSign = -lowerSign
        val f = facingSign

        val pulse = 0.5f + 0.5f * sin(t * TAU * freq + phase)
        val liftDeg = 3f + e * 30f * pulse + 32f * surprise
        val swingDeg = (6f + 16f * e) * sin(t * TAU * freq * 0.7f + phase)
        poseBone(upperRole, -f * deg(swingDeg), 0f, liftSign * deg(liftDeg))

        val elbowDeg = 14f + e * 42f * (0.5f + 0.5f * sin(t * TAU * freq * 1.3f + phase + 1f)) + 25f * surprise
        poseBone(lowerRole, -f * deg(elbowDeg), 0f, 0f)
    }

    private fun trackProbes() {
        val tm = engine.transformManager
        bones["head"]?.let {
            tm.getWorldTransform(it.ti, tmpMatrix)
            updateProbe(0)
        }
        bones["leftLowerArm"]?.let {
            tm.getWorldTransform(it.ti, tmpMatrix)
            updateProbe(3)
        }
    }

    private fun updateProbe(offset: Int) {
        for (i in 0 until 3) {
            val v = tmpMatrix[12 + i]
            if (v < probeMin[offset + i]) probeMin[offset + i] = v
            if (v > probeMax[offset + i]) probeMax[offset + i] = v
        }
    }

    private fun applyCameraDrift(t: Float) {
        val dx = sin(t * 0.12f) * 0.05f
        val dy = sin(t * 0.09f + 1f) * 0.02f
        val dz = sin(t * 0.08f) * 0.05f * facingSign
        camera.lookAt(
            (baseEye[0] + dx).toDouble(), (baseEye[1] + dy).toDouble(), (baseEye[2] + dz).toDouble(),
            lookAtCenter[0].toDouble(), lookAtCenter[1].toDouble(), lookAtCenter[2].toDouble(),
            0.0, 1.0, 0.0
        )
    }

    fun detachSurface() {
        val sc = swapChain ?: return
        engine.flushAndWait()
        engine.destroySwapChain(sc)
        swapChain = null
        engine.flushAndWait()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        try { detachSurface() } catch (ignored: Throwable) { }
        try { removeLight(keyLight) } catch (ignored: Throwable) { }
        try { removeLight(fillLight) } catch (ignored: Throwable) { }
        try {
            asset?.let {
                scene.removeEntities(it.entities)
                assetLoader?.destroyAsset(it)
            }
            assetLoader?.destroy()
            materialProvider?.destroyMaterials()
            materialProvider?.destroy()
        } catch (ignored: Throwable) { }
        try { engine.destroyEntity(cameraEntity) } catch (ignored: Throwable) { }
        try { engine.destroyView(view) } catch (ignored: Throwable) { }
        try { engine.destroyScene(scene) } catch (ignored: Throwable) { }
        try { skybox?.let { engine.destroySkybox(it) } } catch (ignored: Throwable) { }
        try { engine.destroyRenderer(renderer) } catch (ignored: Throwable) { }
        try { engine.destroy() } catch (ignored: Throwable) { }
    }
}
