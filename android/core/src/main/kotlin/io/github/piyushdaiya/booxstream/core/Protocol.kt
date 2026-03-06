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

package io.github.piyushdaiya.booxstream.core

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    const val PORT: Int = 27183

    // "BOOX" when written little-endian as u32
    const val MAGIC: UInt = 0x584F4F42u
    const val VERSION: UShort = 1u
    const val TIMEBASE_DEN_US: UInt = 1_000_000u

    const val MAX_TOKEN_LEN: Int = 128
    const val MAX_FRAME_BYTES: Int = 8 * 1024 * 1024 // 8 MiB safety cap

    enum class CodecId(val id: UShort) {
        VP8(1u), VP9(2u), H264(3u), H265(4u);

        companion object {
            fun from(id: UShort): CodecId =
                entries.firstOrNull { it.id == id } ?: error("Unknown codec id: $id")
        }
    }

    data class Hello(val token: ByteArray)
    data class HelloOk(val codec: CodecId, val width: UShort, val height: UShort, val timebaseDen: UInt)
    data class Frame(val ptsUs: ULong, val keyframe: Boolean, val payload: ByteArray)

    fun writeHello(out: OutputStream, token: ByteArray) {
        require(token.isNotEmpty()) { "token must not be empty" }
        require(token.size <= MAX_TOKEN_LEN) { "token too long" }

        val bb = ByteBuffer.allocate(4 + 2 + 2 + token.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(MAGIC.toInt())
        bb.putShort(VERSION.toShort())
        bb.putShort(token.size.toShort())
        bb.put(token)

        out.write(bb.array())
        out.flush()
    }

    fun readHello(input: InputStream): Hello {
        val magic = input.readU32LE()
        require(magic == MAGIC) { "Bad magic: $magic" }

        val version = input.readU16LE()
        require(version == VERSION) { "Unsupported version: $version" }

        val tokenLen = input.readU16LE().toInt()
        require(tokenLen in 1..MAX_TOKEN_LEN) { "Invalid token length: $tokenLen" }

        val token = input.readExact(tokenLen)
        return Hello(token)
    }

    fun writeHelloOk(out: OutputStream, ok: HelloOk) {
        val bb = ByteBuffer.allocate(4 + 2 + 2 + 2 + 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(MAGIC.toInt())
        bb.putShort(VERSION.toShort())
        bb.putShort(ok.codec.id.toShort())
        bb.putShort(ok.width.toShort())
        bb.putShort(ok.height.toShort())
        bb.putInt(ok.timebaseDen.toInt())

        out.write(bb.array())
        out.flush()
    }

    fun readHelloOk(input: InputStream): HelloOk {
        val magic = input.readU32LE()
        require(magic == MAGIC) { "Bad magic: $magic" }

        val version = input.readU16LE()
        require(version == VERSION) { "Unsupported version: $version" }

        val codecId = input.readU16LE()
        val width = input.readU16LE()
        val height = input.readU16LE()
        val timebaseDen = input.readU32LE()

        return HelloOk(CodecId.from(codecId), width, height, timebaseDen)
    }

    fun writeFrame(out: OutputStream, frame: Frame) {
        require(frame.payload.size <= MAX_FRAME_BYTES) { "Frame too large" }

        val flags: UByte = if (frame.keyframe) 1u else 0u
        val header = ByteBuffer.allocate(4 + 8 + 1 + 1 + 2).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(frame.payload.size)
        header.putLong(frame.ptsUs.toLong())
        header.put(flags.toByte())
        header.put(0)      // reserved8
        header.putShort(0) // reserved16

        out.write(header.array())
        out.write(frame.payload)
    }

    fun readFrame(input: InputStream): Frame {
        val len = input.readU32LE().toInt()
        require(len in 0..MAX_FRAME_BYTES) { "Invalid frame length: $len" }

        val ptsUs = input.readU64LE()
        val flags = input.readU8()
        input.readU8()      // reserved8
        input.readU16LE()   // reserved16

        val payload = input.readExact(len)
        val keyframe = (flags.toInt() and 0x01) != 0

        return Frame(ptsUs, keyframe, payload)
    }
}

/** Stream helpers (strict, testable) */
private fun InputStream.readExact(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(buf, off, n - off)
        if (r <= 0) throw EOFException("Unexpected EOF (wanted=$n got=$off)")
        off += r
    }
    return buf
}

private fun InputStream.readU8(): UByte {
    val v = read()
    if (v < 0) throw EOFException("EOF")
    return v.toUByte()
}

private fun InputStream.readU16LE(): UShort {
    val b0 = readU8().toInt()
    val b1 = readU8().toInt()
    return ((b1 shl 8) or b0).toUShort()
}

private fun InputStream.readU32LE(): UInt {
    val b0 = readU8().toUInt()
    val b1 = readU8().toUInt()
    val b2 = readU8().toUInt()
    val b3 = readU8().toUInt()
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}

private fun InputStream.readU64LE(): ULong {
    var v: ULong = 0u
    for (i in 0 until 8) {
        v = v or (readU8().toULong() shl (8 * i))
    }
    return v
}