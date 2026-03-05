package io.github.piyushdaiya.booxstream.core.ivf

import java.io.OutputStream

/**
 * IVF container writer for VP8/VP9 elementary frames.
 *
 * Writes:
 *  - 32-byte IVF header
 *  - for each frame: 12-byte frame header (size LE32 + timestamp LE64) + payload
 *
 * Timebase:
 *  - rate = fps, scale = 1  => ffmpeg/ffplay treats frame rate as fps/1.
 *  - timestamps are frame indices (0,1,2,...) in "ticks" of 1/fps seconds.
 */
class IvfWriter(
    private val out: OutputStream,
    private val fourcc: FourCC,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
) {
    enum class FourCC(val code: String) {
        VP8("VP80"),
        VP9("VP90"),
    }

    private var headerWritten = false
    private var frameCount: Long = 0

    fun writeHeader() {
        if (headerWritten) return
        headerWritten = true

        require(fps in 1..240) { "fps out of range: $fps" }
        require(width in 1..8192) { "width out of range: $width" }
        require(height in 1..8192) { "height out of range: $height" }

        val header = ByteArray(32)

        // signature "DKIF"
        header[0] = 'D'.code.toByte()
        header[1] = 'K'.code.toByte()
        header[2] = 'I'.code.toByte()
        header[3] = 'F'.code.toByte()

        le16(header, 4, 0)      // version
        le16(header, 6, 32)     // header length

        val cc = fourcc.code
        header[8]  = cc[0].code.toByte()
        header[9]  = cc[1].code.toByte()
        header[10] = cc[2].code.toByte()
        header[11] = cc[3].code.toByte()

        le16(header, 12, width)
        le16(header, 14, height)

        // IVF "timebase": rate/scale. ffmpeg expects frame rate = rate/scale.
        le32(header, 16, fps)   // rate (denominator)
        le32(header, 20, 1)     // scale (numerator)

        le32(header, 24, 0) // frame count unknown (0 acceptable)
        le32(header, 28, 0) // unused

        out.write(header)
        out.flush()
    }

    /**
     * Writes one IVF frame with an auto-incrementing timestamp (frame index).
     */
    fun writeFrame(frame: ByteArray) {
        if (!headerWritten) writeHeader()

        val fh = ByteArray(12)
        le32(fh, 0, frame.size)
        le64(fh, 4, frameCount) // timestamp = frame index

        out.write(fh)
        out.write(frame)
        frameCount++
    }

    companion object {
        private fun le16(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        private fun le32(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v ushr 8) and 0xFF).toByte()
            b[off + 2] = ((v ushr 16) and 0xFF).toByte()
            b[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        private fun le64(b: ByteArray, off: Int, v: Long) {
            var x = v
            for (i in 0 until 8) {
                b[off + i] = (x and 0xFF).toByte()
                x = x ushr 8
            }
        }
    }
}