package io.github.piyushdaiya.booxstream.core.ivf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream

class IvfWriterTest {

    @Test
    fun `writes IVF header`() {
        val baos = ByteArrayOutputStream()
        val out = BufferedOutputStream(baos)

        val w = IvfWriter(
            out = out,
            fourcc = IvfWriter.FourCC.VP8,
            width = 1280,
            height = 720,
            fps = 12
        )

        w.writeHeader()
        out.flush()

        val bytes = baos.toByteArray()
        assertTrue(bytes.size >= 32)

        // Signature
        assertEquals('D'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
        assertEquals('I'.code.toByte(), bytes[2])
        assertEquals('F'.code.toByte(), bytes[3])

        // FourCC "VP80" (little endian bytes)
        assertEquals('V'.code.toByte(), bytes[8])
        assertEquals('P'.code.toByte(), bytes[9])
        assertEquals('8'.code.toByte(), bytes[10])
        assertEquals('0'.code.toByte(), bytes[11])

        // Width/Height (little endian)
        val width = (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8)
        val height = (bytes[14].toInt() and 0xFF) or ((bytes[15].toInt() and 0xFF) shl 8)
        assertEquals(1280, width)
        assertEquals(720, height)
    }

    @Test
    fun `writes frame with IVF frame header`() {
        val baos = ByteArrayOutputStream()
        val out = BufferedOutputStream(baos)

        val w = IvfWriter(
            out = out,
            fourcc = IvfWriter.FourCC.VP8,
            width = 640,
            height = 480,
            fps = 10
        )
        w.writeHeader()

        val frame = ByteArray(100) { 0x11 }
        w.writeFrame(frame)
        out.flush()

        val bytes = baos.toByteArray()

        // IVF header is 32 bytes, then frame header is 12 bytes:
        // [0..31] file header
        // [32..43] frame header (4 size + 8 timestamp)
        // [44..] frame payload
        assertTrue(bytes.size >= 32 + 12 + frame.size)

        val size =
            (bytes[32].toInt() and 0xFF) or
            ((bytes[33].toInt() and 0xFF) shl 8) or
            ((bytes[34].toInt() and 0xFF) shl 16) or
            ((bytes[35].toInt() and 0xFF) shl 24)

        assertEquals(frame.size, size)

        // payload starts at 32 + 12
        val payloadOffset = 44
        for (i in frame.indices) {
            assertEquals(0x11.toByte(), bytes[payloadOffset + i])
        }
    }
}