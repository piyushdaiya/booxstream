package io.github.piyushdaiya.booxstream.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ProtocolTest {

    @Test
    fun hello_roundtrip() {
        val token = "deadbeef".encodeToByteArray()
        val out = ByteArrayOutputStream()
        Protocol.writeHello(out, token)

        val inp = ByteArrayInputStream(out.toByteArray())
        val hello = Protocol.readHello(inp)

        assertEquals(token.decodeToString(), hello.token.decodeToString())
    }

    @Test
    fun hello_rejects_empty_token() {
        val out = ByteArrayOutputStream()
        assertFails { Protocol.writeHello(out, byteArrayOf()) }
    }

    @Test
    fun frame_roundtrip() {
        val payload = ByteArray(1024) { (it % 251).toByte() }
        val f = Protocol.Frame(ptsUs = 123_456uL, keyframe = true, payload = payload)

        val out = ByteArrayOutputStream()
        Protocol.writeFrame(out, f)

        val inp = ByteArrayInputStream(out.toByteArray())
        val decoded = Protocol.readFrame(inp)

        assertEquals(f.ptsUs, decoded.ptsUs)
        assertEquals(f.keyframe, decoded.keyframe)
        assertEquals(f.payload.size, decoded.payload.size)
        assertEquals(f.payload[100], decoded.payload[100])
    }

    @Test
    fun frame_rejects_oversize() {
        val big = ByteArray(Protocol.MAX_FRAME_BYTES + 1)
        val out = ByteArrayOutputStream()
        assertFails { Protocol.writeFrame(out, Protocol.Frame(0uL, false, big)) }
    }
}