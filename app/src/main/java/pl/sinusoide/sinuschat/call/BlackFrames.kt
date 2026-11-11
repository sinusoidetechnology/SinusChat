package pl.sinusoide.sinuschat.call

import org.webrtc.VideoFrame

object BlackFrames {

    fun isBlack(frame: VideoFrame): Boolean {
        val i420 = try { frame.buffer.toI420() } catch (_: Throwable) { null } ?: return false
        try {
            val w = i420.width
            val h = i420.height
            if (w < 8 || h < 8) return false
            val y = i420.dataY
            val stride = i420.strideY
            var min = 255
            var max = 0
            for (r in 1..7) for (c in 1..7) {
                val v = y.get((h * r / 8) * stride + w * c / 8).toInt() and 0xFF
                if (v > max) max = v
                if (v < min) min = v
            }
            return max <= 20 && max - min <= 2
        } catch (_: Throwable) {
            return false
        } finally {
            i420.release()
        }
    }
}
