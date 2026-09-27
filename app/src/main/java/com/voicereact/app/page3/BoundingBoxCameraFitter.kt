package com.voicereact.app.page3

import com.google.android.filament.gltfio.FilamentAsset
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

object BoundingBoxCameraFitter {

    data class Fit(
        val eyeX: Float, val eyeY: Float, val eyeZ: Float,
        val centerX: Float, val centerY: Float, val centerZ: Float,
        val verticalFovDegrees: Double
    )

    fun compute(
        asset: FilamentAsset,
        viewportWidth: Int,
        viewportHeight: Int,
        verticalFovDegrees: Double = 38.0,
        safetyMargin: Float = 1.10f
    ): Fit {
        val box = asset.boundingBox
        val center = box.center
        val halfExtent = box.halfExtent

        val halfHeight = halfExtent[1]
        val halfWidth = max(halfExtent[0], halfExtent[2])

        val vFovRad = Math.toRadians(verticalFovDegrees)
        val distForHeight = (halfHeight / tan(vFovRad / 2.0)).toFloat()

        val aspect = viewportWidth.toDouble() / viewportHeight.toDouble()
        val hFovRad = 2.0 * atan(tan(vFovRad / 2.0) * aspect)
        val distForWidth = (halfWidth / tan(hFovRad / 2.0)).toFloat()

        val distance = max(distForHeight, distForWidth) * safetyMargin

        return Fit(
            eyeX = center[0],
            eyeY = center[1],
            eyeZ = center[2] + distance,
            centerX = center[0],
            centerY = center[1],
            centerZ = center[2],
            verticalFovDegrees = verticalFovDegrees
        )
    }
}
