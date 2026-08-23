package com.pixeltrigger.app.engine

/**
 * Independent V5 shoulder detector.
 *
 * Semantics requested for the shoulder half:
 * - any non-red frame arms immediately;
 * - the first red frame while armed fires;
 * - the detector will not fire again until red disappears and a non-red frame rearms it.
 */
class ShoulderDetectionEngine {
    enum class State { WAITING_NON_RED, ARMED }

    sealed interface Event {
        data object None : Event
        data object Armed : Event
        data object Fired : Event
    }

    var state: State = State.WAITING_NON_RED
        private set

    fun process(redDetected: Boolean): Event = when (state) {
        State.WAITING_NON_RED -> {
            if (redDetected) Event.None
            else {
                state = State.ARMED
                Event.Armed
            }
        }

        State.ARMED -> {
            if (!redDetected) Event.None
            else {
                state = State.WAITING_NON_RED
                Event.Fired
            }
        }
    }

    fun reset() {
        state = State.WAITING_NON_RED
    }

    companion object {
        const val RED_MIN_CHANNEL = 120
        const val RED_MIN_DOMINANCE = 35

        fun isRed(sample: DetectionEngine.ColorSample): Boolean {
            if (sample.probeCount <= 0) {
                return isRgbRed(sample.averageRed, sample.averageGreen, sample.averageBlue)
            }

            val quorum = when {
                sample.probeCount >= 5 -> 3
                sample.probeCount >= 3 -> 2
                else -> 1
            }
            var red = 0
            var i = 0
            while (i < sample.probeCount.coerceAtMost(DetectionEngine.MAX_PROBE_POINTS)) {
                val packed = sample.probeAt(i)
                val r = (packed ushr 16) and 0xff
                val g = (packed ushr 8) and 0xff
                val b = packed and 0xff
                if (isRgbRed(r, g, b)) {
                    red++
                    if (red >= quorum) return true
                }
                i++
            }
            return false
        }

        private fun isRgbRed(r: Int, g: Int, b: Int): Boolean =
            r >= RED_MIN_CHANNEL &&
                r - g >= RED_MIN_DOMINANCE &&
                r - b >= RED_MIN_DOMINANCE
    }
}
