/*
 * Copyright 2026 Piyush Daiya
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

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