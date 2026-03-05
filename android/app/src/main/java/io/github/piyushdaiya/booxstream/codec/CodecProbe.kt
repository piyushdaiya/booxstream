package io.github.piyushdaiya.booxstream.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

object CodecProbe {
    private const val TAG = "BooxCodecProbe"

    enum class Kind { ENCODER, DECODER }

    data class ProbeResult(
        val name: String,
        val kind: Kind,
        val mime: String,
        val isHardwareAccelerated: Boolean?,
        val isSoftwareOnly: Boolean?,
        val isVendor: Boolean?,
        val createOk: Boolean,
        val configureOk: Boolean?,
        val error: String?
    )

    data class ProbeReport(
        val device: String,
        val sdk: Int,
        val results: List<ProbeResult>
    )

    // Keep the list focused on what matters for your pipeline decisions.
    private val mimesToCheck = listOf(
        "video/x-vnd.on2.vp8",
        "video/x-vnd.on2.vp9",
        "video/avc",
        "video/hevc",
        "video/av01",
    )

    fun run(): ProbeReport {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        val infos = list.codecInfos

        val results = mutableListOf<ProbeResult>()

        for (mime in mimesToCheck) {
            for (info in infos) {
                val supports = try {
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                } catch (_: Exception) {
                    false
                }
                if (!supports) continue

                val kind = if (info.isEncoder) Kind.ENCODER else Kind.DECODER
                val attrs = codecAttributes(info)

                val (createOk, createErr) = tryCreate(info.name)
                val (configureOk, configureErr) = if (createOk && info.isEncoder) {
                    tryConfigureEncoder(info.name, mime)
                } else {
                    null to null
                }

                val err = createErr ?: configureErr

                results += ProbeResult(
                    name = info.name,
                    kind = kind,
                    mime = mime,
                    isHardwareAccelerated = attrs.isHardwareAccelerated,
                    isSoftwareOnly = attrs.isSoftwareOnly,
                    isVendor = attrs.isVendor,
                    createOk = createOk,
                    configureOk = configureOk,
                    error = err
                )
            }
        }

        return ProbeReport(
            device = Build.MODEL ?: "unknown",
            sdk = Build.VERSION.SDK_INT,
            results = results.sortedWith(
                compareBy<ProbeResult> { it.mime }
                    .thenBy { it.kind.name }
                    .thenBy { it.name }
            )
        )
    }

    private data class Attrs(
        val isHardwareAccelerated: Boolean?,
        val isSoftwareOnly: Boolean?,
        val isVendor: Boolean?
    )

    private fun codecAttributes(info: MediaCodecInfo): Attrs {
        return if (Build.VERSION.SDK_INT >= 29) {
            Attrs(
                isHardwareAccelerated = info.isHardwareAccelerated,
                isSoftwareOnly = info.isSoftwareOnly,
                isVendor = info.isVendor
            )
        } else {
            Attrs(null, null, null)
        }
    }

    private fun tryCreate(name: String): Pair<Boolean, String?> {
    var codec: MediaCodec? = null
    return try {
        codec = MediaCodec.createByCodecName(name)
        Log.i(TAG, "create OK: $name")
        true to null
    } catch (t: Throwable) {
        Log.w(TAG, "create FAIL: $name -> ${t.javaClass.simpleName}: ${t.message}")
        false to "${t.javaClass.simpleName}: ${t.message}"
    } finally {
        try { codec?.release() } catch (_: Throwable) {}
    }
}

    /**
     * Minimal encoder configure attempt. Many vendor restrictions show up here.
     * We do NOT start() the codec; configure is enough to detect access/param issues.
     */
    private fun tryConfigureEncoder(name: String, mime: String): Pair<Boolean?, String?> {
    var codec: MediaCodec? = null
    return try {
        codec = MediaCodec.createByCodecName(name)
        val fmt = minimalFormatFor(mime)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        Log.i(TAG, "configure OK: $name mime=$mime format=$fmt")
        true to null
    } catch (t: Throwable) {
        Log.w(TAG, "configure FAIL: $name mime=$mime -> ${t.javaClass.simpleName}: ${t.message}")
        false to "${t.javaClass.simpleName}: ${t.message}"
    } finally {
        try { codec?.release() } catch (_: Throwable) {}
    }
}

    private fun minimalFormatFor(mime: String): MediaFormat {
        // Conservative defaults; tiny resolution to reduce requirements.
        val width = 640
        val height = 480
        val bitrate = 400_000
        val fps = 15
        val iFrameInterval = 2

        return MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)

            // Some encoders require a color format; choose a common flexible one if available.
            // If it fails, that’s useful info—we’ll adapt after seeing results.
            if (Build.VERSION.SDK_INT >= 21) {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
            }
        }
    }
}