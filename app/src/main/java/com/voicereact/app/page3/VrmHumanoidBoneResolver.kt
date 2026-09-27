package com.voicereact.app.page3

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VrmHumanoidBoneResolver {

    private const val JSON_CHUNK_TYPE = 0x4E4F534A // "JSON" باللتل-إنديان حسب مواصفة GLB

    fun resolve(vrmBytes: ByteArray): HumanoidBoneNames {
        val json = extractJsonChunk(vrmBytes)
        val nodes = json.getJSONArray("nodes")

        fun nodeNameOf(nodeIndex: Int): String {
            if (nodeIndex < 0) return ""
            return nodes.getJSONObject(nodeIndex).optString("name", "")
        }

        val extensions = json.optJSONObject("extensions")

        extensions?.optJSONObject("VRMC_vrm")?.optJSONObject("humanoid")?.optJSONObject("humanBones")?.let { humanBones ->
            fun boneNode(bone: String): Int = humanBones.optJSONObject(bone)?.optInt("node", -1) ?: -1
            return HumanoidBoneNames(
                head = nodeNameOf(boneNode("head")),
                hips = nodeNameOf(boneNode("hips")),
                leftUpperArm = nodeNameOf(boneNode("leftUpperArm")),
                rightUpperArm = nodeNameOf(boneNode("rightUpperArm"))
            )
        }

        extensions?.optJSONObject("VRM")?.optJSONObject("humanoid")?.optJSONArray("humanBones")?.let { humanBonesArray ->
            var headNode = -1; var hipsNode = -1; var leftUpperArmNode = -1; var rightUpperArmNode = -1
            for (i in 0 until humanBonesArray.length()) {
                val entry = humanBonesArray.getJSONObject(i)
                val node = entry.optInt("node", -1)
                when (entry.optString("bone")) {
                    "head" -> headNode = node
                    "hips" -> hipsNode = node
                    "leftUpperArm" -> leftUpperArmNode = node
                    "rightUpperArm" -> rightUpperArmNode = node
                }
            }
            return HumanoidBoneNames(
                head = nodeNameOf(headNode),
                hips = nodeNameOf(hipsNode),
                leftUpperArm = nodeNameOf(leftUpperArmNode),
                rightUpperArm = nodeNameOf(rightUpperArmNode)
            )
        }

        error("الملف مو VRM 0.x أو 1.0 قياسي — ما لقيت humanoid.humanBones")
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
