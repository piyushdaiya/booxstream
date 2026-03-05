package io.github.piyushdaiya.booxstream.core.ivf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class IvfWriterTest {

    @Test
    fun writesValidHeaderPrefix() {
        val baos = ByteArrayOutputStream()
        val w = IvfWriter(baos, IvfWriter.FourCC.VP8, 640, 480)
        w.writeHeader()
        val bytes = baos.toByteArray()

        assertEquals(32, bytes.size)
        assertEquals('D'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
        assertEquals('I'.code.toByte(), bytes[2])
        assertEquals('F'.code.toByte(), bytes[3])

        val fourcc = String(bytes.sliceArray(8..11))
        assertEquals("VP80", fourcc)

        // header length = 32
        assertEquals(32, (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8))
    }

    @Test
    fun writesFrameHeaderAndPayload() {
        val baos = ByteArrayOutputStream()
        val w = IvfWriter(baos, IvfWriter.FourCC.VP8, 64, 64)

        val frame = byteArrayOf(1, 2, 3, 4, 5)
        w.writeFrame(frame, 1234L)

        val bytes = baos.toByteArray()
        assertTrue(bytes.size > 32)

        // frame size at offset 32
        val size = (bytes[32].toInt() and 0xFF) or
                ((bytes[33].toInt() and 0xFF) shl 8) or
                ((bytes[34].toInt() and 0xFF) shl 16) or
                ((bytes[35].toInt() and 0xFF) shl 24)
        assertEquals(5, size)

        // payload starts at 32 + 12
        val payload = bytes.sliceArray(44 until 49)
        assertEquals(frame.toList(), payload.toList())
    }
}