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
        "leftUpperArm", "leftLowerArm", "rightUpperArm", "rightLowerArm",
        "leftUpperLeg", "leftLowerLeg", "rightUpperLeg", "rightLowerLeg"
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
            return nodes.optJSONObject(index)?.optString("name", "") ?: ""
        }

        /** Fully defensive version: every single step is null-checked individually.
         *  Root cause of the character-11 crash was here — some VRM exports put
         *  targetNames under primitives[0].extras instead of the mesh-level extras,
         *  and some have no primitives array at all, or an empty one. The old code
         *  chained these with ?. but one of the intermediate Java/JSONObject calls
         *  in that chain still threw before the null-check could apply. */
        fun targetName(meshIndex: Int, targetIndex: Int): String {
            if (meshIndex < 0 || meshIndex >= meshes.length() || targetIndex < 0) return ""
            val mesh = meshes.optJSONObject(meshIndex) ?: return ""

            val meshLevelExtras = mesh.optJSONObject("extras")
            val meshLevelNames = meshLevelExtras?.optJSONArray("targetNames")
            if (meshLevelNames != null && targetIndex < meshLevelNames.length()) {
                return meshLevelNames.optString(targetIndex, "")
            }

            val primitives = mesh.optJSONArray("primitives")
            if (primitives == null || primitives.length() == 0) return ""
            val firstPrimitive = primitives.optJSONObject(0) ?: return ""
            val primitiveExtras = firstPrimitive.optJSONObject("extras") ?: return ""
            val primitiveNames = primitiveExtras.optJSONArray("targetNames") ?: return ""
            if (targetIndex >= primitiveNames.length()) return ""
            return primitiveNames.optString(targetIndex, "")
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
                    try {
                        val bind = binds.optJSONObject(i) ?: continue
                        val node = bind.optInt("node", -1)
                        val meshIndex = if (node >= 0 && node < nodes.length()) {
                            nodes.optJSONObject(node)?.optInt("mesh", -1) ?: -1
                        } else -1
                        val name = targetName(meshIndex, bind.optInt("index", -1))
                        if (name.isNotEmpty()) {
                            val scale = bind.optDouble("weight", 1.0).toFloat().coerceIn(0f, 1f)
                            expressions.getOrPut(role) { mutableListOf() }.add(MorphBinding(name, scale))
                        }
                    } catch (ignored: Throwable) {
                        // One malformed bind entry must never take down the whole character load.
                    }
                }
            }
        } else if (vrm0 != null) {
            isVrm0 = true
            val humanBonesArray = vrm0.optJSONObject("humanoid")?.optJSONArray("humanBones")
            if (humanBonesArray != null) {
                for (i in 0 until humanBonesArray.length()) {
                    val entry = humanBonesArray.optJSONObject(i) ?: continue
                    boneNodes[entry.optString("bone")] = entry.optInt("node", -1)
                }
            }
            val groups = vrm0.optJSONObject("blendShapeMaster")?.optJSONArray("blendShapeGroups")
            if (groups != null) {
                for (g in 0 until groups.length()) {
                    try {
                        val group = groups.optJSONObject(g) ?: continue
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
                            try {
                                val bind = binds.optJSONObject(i) ?: continue
                                val name = targetName(bind.optInt("mesh", -1), bind.optInt("index", -1))
                                if (name.isNotEmpty()) {
                                    val scale = (bind.optDouble("weight", 100.0) / 100.0).toFloat().coerceIn(0f, 1f)
                                    expressions.getOrPut(role) { mutableListOf() }.add(MorphBinding(name, scale))
                                }
                            } catch (ignored: Throwable) {
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            }
        } else {
            error("File is not a standard VRM 0.x or 1.0 — humanoid.humanBones not found")
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
            rightLowerArm = nodeName(boneNodes["rightLowerArm"] ?: -1),
            leftUpperLeg = nodeName(boneNodes["leftUpperLeg"] ?: -1),
            leftLowerLeg = nodeName(boneNodes["leftLowerLeg"] ?: -1),
            rightUpperLeg = nodeName(boneNodes["rightUpperLeg"] ?: -1),
            rightLowerLeg = nodeName(boneNodes["rightLowerLeg"] ?: -1)
        )
        return VrmInfo(bones, expressions, isVrm0)
    }

    private fun extractJsonChunk(bytes: ByteArray): JSONObject {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(12)
        val chunkLength = buffer.int
        val chunkType = buffer.int
        require(chunkType == JSON_CHUNK_TYPE) { "First chunk in file is not JSON — not a valid GLB" }
        val jsonBytes = ByteArray(chunkLength)
        buffer.get(jsonBytes)
        return JSONObject(String(jsonBytes, Charsets.UTF_8))
    }
}
