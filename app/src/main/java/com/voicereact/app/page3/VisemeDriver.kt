package com.voicereact.app.page3

class VisemeDriver(private val timeline: List<VisemeFrame>) {
    private var pointer = 0

    fun apply(currentTimeSeconds: Float, applyWeight: (String, Float) -> Unit) {
        if (timeline.isEmpty()) return
        while (pointer < timeline.size - 1 && timeline[pointer + 1].timeSeconds <= currentTimeSeconds) {
            pointer++
        }
        val frame = timeline[pointer]
        applyWeight(frame.morphTargetName, frame.weight)
    }
}
