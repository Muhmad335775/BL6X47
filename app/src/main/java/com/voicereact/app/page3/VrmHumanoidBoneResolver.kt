package com.voicereact.app.page3

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MorphBinding(val targetName: String, val scale: Float)

data class VrmInfo(
    val bones: HumanoidBoneNames,
    val expressions: Map<String, List<MorphBinding>>,
    val isVrm0: Boolean
)

object VrmHumanoidBoneResolver {

    private const val JSON_CHUNK_TYPE = 0x4E4F534A

    private val BONE_KEYS = listOf(
        "hips", "spine", "chest", "neck", "head",
        "leftUpperArm", "leftLowerArm", "rightUpperArm", "rightLowerArm"
    )

    private val VRM1_PRESETS = listOf(
        "A" to "aa", "I" to "ih", "U" to "ou", "E" to "ee", "O" to "oh",
        "BLINK" to "blink", "SURPRISED" to "surprised"
    )

    fun resolve(vrmBytes: ByteArray): VrmInfo {
        val json = extractJsonChunk(vrmBytes)
        val nodes = json.optJSONArray("nodes") ?: JSONArray()
        val meshes = json.optJSONArray("meshes") ?: JSONArray()
        val extensions = json.optJSONObject("extensions") ?: JSONObject()
        val vrm1 = extensions.optJSONObject("VRMC_vrm")
        val vrm0 = extensions.optJSONObject("VRM")

        fun nodeName(index: Int): String {
            if (index < 0 || index >= nodes.length()) return ""
            return nodes.getJSONObject(index).optString("name", "")
        }

        fun targetName(meshIndex: Int, targetIndex: Int): String {
            if (meshIndex < 0 || meshIndex >= meshes.length() || targetIndex < 0) return ""
            val mesh = meshes.getJSONObject(meshIndex)
            val names = mesh.optJSONObject("extras")?.optJSONArray("targetNames")
                ?: mesh.optJSONArray("primitives")?.optJSONObject(0)?.optJSONObject("extras")?.optJSONArray("targetNames")
            if (names == null || targetIndex >= names.length()) return ""
            return names.optString(targetIndex, "")
        }

        val boneNodes = HashMap<String, Int>()
        val expressions = HashMap<String, MutableList<MorphBinding>>()
        val isVrm0: Boolean

        if (vrm1 != null) {
            isVrm0 = false
            val humanBones = vrm1.optJSONObject("humanoid")?.optJSONObject("humanBones")
            for (key in BONE_KEYS) {
                boneNodes[key] = humanBones?.optJSONObject(key)?.optInt("node", -1) ?: -1
            }
            val preset = vrm1.optJSONObject("expressions")?.optJSONObject("preset")
            for ((role, presetName) in VRM1_PRESETS) {
                val binds = preset?.optJSONObject(presetName)?.optJSONArray("morphTargetBinds") ?: continue
                for (i in 0 until binds.length()) {
                    val bind = binds.getJSONObject(i)
                    val node = bind.optInt("node", -1)
                    val meshIndex = if (node >= 0 && node < nodes.length()) nodes.getJSONObject(node).optInt("mesh", -1) else -1
                    val name = targetName(meshIndex, bind.optInt("index", -1))
                    if (name.isNotEmpty()) {
                        val scale = bind.optDouble("weight", 1.0).toFloat().coerceIn(0f, 1f)
                        expressions.getOrPut(role) { mutableListOf() }.add(MorphBinding(name, scale))
                    }
                }
            }
        } else if (vrm0 != null) {
            isVrm0 = true
            val humanBonesArray = vrm0.optJSONObject("humanoid")?.optJSONArray("humanBones")
            if (humanBonesArray != null) {
                for (i in 0 until humanBonesArray.length()) {
                    val entry = humanBonesArray.getJSONObject(i)
                    boneNodes[entry.optString("bone")] = entry.optInt("node", -1)
                }
            }
            val groups = vrm0.optJSONObject("blendShapeMaster")?.optJSONArray("blendShapeGroups")
            if (groups != null) {
                for (g in 0 until groups.length()) {
                    val group = groups.getJSONObject(g)
                    val role = when (group.optString("presetName", "").lowercase()) {
                        "a" -> "A"
                        "i" -> "I"
                        "u" -> "U"
                        "e" -> "E"
                        "o" -> "O"
                        "blink" -> "BLINK"
                        else -> null
                    } ?: continue
                    val binds = group.optJSONArray("binds") ?: continue
                    for (i in 0 until binds.length()) {
                        val bind = binds.getJSONObject(i)
                        val name = targetName(bind.optInt("mesh", -1), bind.optInt("index", -1))
                        if (name.isNotEmpty()) {
                            val scale = (bind.optDouble("weight", 100.0) / 100.0).toFloat().coerceIn(0f, 1f)
                            expressions.getOrPut(role) { mutableListOf() }.add(MorphBinding(name, scale))
                        }
                    }
                }
            }
        } else {
            error("الملف مو VRM 0.x أو 1.0 قياسي — ما لقيت humanoid.humanBones")
        }

        val bones = HumanoidBoneNames(
            hips = nodeName(boneNodes["hips"] ?: -1),
            spine = nodeName(boneNodes["spine"] ?: -1),
            chest = nodeName(boneNodes["chest"] ?: -1),
            neck = nodeName(boneNodes["neck"] ?: -1),
            head = nodeName(boneNodes["head"] ?: -1),
            leftUpperArm = nodeName(boneNodes["leftUpperArm"] ?: -1),
            leftLowerArm = nodeName(boneNodes["leftLowerArm"] ?: -1),
            rightUpperArm = nodeName(boneNodes["rightUpperArm"] ?: -1),
            rightLowerArm = nodeName(boneNodes["rightLowerArm"] ?: -1)
        )
        return VrmInfo(bones, expressions, isVrm0)
    }

    private fun extractJsonChunk(bytes: ByteArray): JSONObject {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(12)
        val chunkLength = buffer.int
        val chunkType = buffer.int
        require(chunkType == JSON_CHUNK_TYPE) { "أول تشنك بالملف مو JSON — الملف مو GLB سليم" }
        val jsonBytes = ByteArray(chunkLength)
        buffer.get(jsonBytes)
        return JSONObject(String(jsonBytes, Charsets.UTF_8))
    }
}
