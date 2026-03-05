package io.github.piyushdaiya.booxstream.stream

import io.github.piyushdaiya.booxstream.core.Protocol
import org.junit.Test
import org.junit.Assert.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtocolHandshakeTest {

    @Test
    fun hello_rejects_wrong_magic() {
        val bad = ByteArrayOutputStream().apply { write(byteArrayOf(0, 0, 0, 0)) }.toByteArray()
        assertThrows(Exception::class.java) {
            Protocol.readHello(ByteArrayInputStream(bad))
        }
    }
}