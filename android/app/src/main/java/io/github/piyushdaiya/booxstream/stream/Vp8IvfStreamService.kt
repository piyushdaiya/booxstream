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

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.piyushdaiya.booxstream.core.ivf.IvfWriter
import java.io.BufferedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class Vp8IvfStreamService : Service() {

    companion object {
        private const val TAG = "Vp8IvfStreamService"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "booxstream"

        // Public running flag for UI quick check
        @JvmField val isRunning = AtomicBoolean(false)

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate" // if absent or <=0 we auto-pick

        const val ABSTRACT_NAME = "booxstream_ivf"

        private const val FLUSH_EVERY_N_FRAMES = 12
        private const val REQUEST_SYNC_EVERY_MS = 1500L

        // ---- NEW: stop if host disconnects and nobody reconnects ----
        private const val STOP_IF_NO_CLIENT_MS = 5000L // tune as desired

        // Stats broadcast (optional)
        const val ACTION_VP8_STATS = "io.github.piyushdaiya.booxstream.ACTION_VP8_STATS"
        const val EXTRA_STATS_FPS = "fps_out"
        const val EXTRA_STATS_KBPS = "kbps_out"
        const val EXTRA_STATS_FRAMES = "frames_total"
        const val EXTRA_STATS_BYTES = "bytes_total"
        const val EXTRA_STATS_SINCE_KF_MS = "since_kf_ms"
        const val EXTRA_STATS_LAST_FRAME = "last_frame_bytes"
        const val EXTRA_STATS_DROPPED_PRE_KF = "dropped_pre_kf"
        const val EXTRA_STATS_KEYFRAMES = "keyframes"
        const val EXTRA_STATS_ERRORS = "write_errors"

        // State broadcast (new; for UI/host diagnostics)
        const val ACTION_VP8_STATE = "io.github.piyushdaiya.booxstream.ACTION_VP8_STATE"
        const val EXTRA_STATE_RUNNING = "running"
        const val EXTRA_STATE_REASON = "reason"
        const val REASON_CLIENT_CONNECTED = "client_connected"
        const val REASON_CLIENT_DISCONNECTED = "client_disconnected"
    }

    private val running = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var codec: MediaCodec? = null

    private var server: LocalServerSocket? = null

    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null

    // ---- Per-client sink ----
    private data class ClientSink(
        val socket: LocalSocket,
        val out: BufferedOutputStream,
        val ivf: IvfWriter,
        var sawKeyframe: Boolean,
        var framesSinceFlush: Int
    )

    private val sinkLock = Any()
    @Volatile private var sink: ClientSink? = null

    @Volatile private var keyframeTickerRunning: Boolean = false
    @Volatile private var statsTickerRunning: Boolean = false

    // ---- NEW: idle-stop ticker state ----
    @Volatile private var idleStopTickerRunning: Boolean = false
    @Volatile private var noClientDeadlineMs: Long = Long.MAX_VALUE

    // stats
    private val intervalFrames = AtomicLong(0)
    private val intervalBytes = AtomicLong(0)
    private val totalFrames = AtomicLong(0)
    private val totalBytes = AtomicLong(0)
    private val keyframes = AtomicLong(0)
    private val droppedBeforeKeyframe = AtomicLong(0)
    private val writeErrors = AtomicInteger(0)
    private val lastKeyframeAtMs = AtomicLong(0)
    private val lastFrameBytes = AtomicInteger(0)
    private var statsLastTickMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        if (running.getAndSet(true)) return START_STICKY

        // Publish running state for UI
        isRunning.set(true)
        broadcastState(true, "onStartCommand")

        startForeground(NOTIF_ID, buildNotification("Streaming starting…"))

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        val width = intent.getIntExtra(EXTRA_WIDTH, 1280).coerceIn(320, 2600)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 720).coerceIn(320, 2600)
        val fps = intent.getIntExtra(EXTRA_FPS, 12).coerceIn(5, 60)

        val requestedBitrate = intent.getIntExtra(EXTRA_BITRATE, -1)
        val bitrate = if (requestedBitrate > 0) {
            requestedBitrate.coerceIn(200_000, 12_000_000)
        } else {
            pickDefaultBitrate(width, height, fps)
        }

        Thread {
            try {
                if (resultData == null || resultCode == Activity.RESULT_CANCELED) {
                    throw IllegalStateException("Missing MediaProjection permission data")
                }

                resetPerRun()

                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mgr.getMediaProjection(resultCode, resultData)

                codecThread = HandlerThread("booxstream-codec").apply { start() }
                codecHandler = Handler(codecThread!!.looper)

                // ---- NEW: start idle-stop ticker (host disconnect handling) ----
                startIdleStopTicker()

                // Start encoder FIRST (we can drop frames until a client connects)
                startStatsTicker()
                startEncoder(width, height, fps, bitrate)

                // Start localabstract server, accept clients forever
                server = LocalServerSocket(ABSTRACT_NAME)
                Log.i(TAG, "LISTENING on localabstract:$ABSTRACT_NAME (use adb forward tcp:27183 localabstract:$ABSTRACT_NAME)")
                updateNotification("Listening on localabstract:$ABSTRACT_NAME")

                // If no host connects, auto-stop after timeout.
                noClientDeadlineMs = SystemClock.elapsedRealtime() + STOP_IF_NO_CLIENT_MS

                while (running.get()) {
                    val sock = server!!.accept() // blocks
                    Log.i(TAG, "Client connected (localabstract)")

                    attachNewClient(sock, width, height, fps)

                    // Force a keyframe right after connect so late-join works.
                    requestSyncFrameSafely()
                }

            } catch (t: Throwable) {
                Log.e(TAG, "Stream failed", t)
            } finally {
                stopEverything()
                stopSelf()
            }
        }.start()

        return START_STICKY
    }

    private fun broadcastState(running: Boolean, reason: String) {
    sendBroadcast(Intent(ACTION_VP8_STATE).apply {
        setPackage(packageName)
        putExtra(EXTRA_STATE_RUNNING, running)
        putExtra(EXTRA_STATE_REASON, reason)
    })
}

    private fun attachNewClient(sock: LocalSocket, width: Int, height: Int, fps: Int) {
        synchronized(sinkLock) {
            try { sink?.socket?.close() } catch (_: Throwable) {}
            sink = null

            val out = BufferedOutputStream(sock.outputStream, 256 * 1024)
            val ivf = IvfWriter(out, IvfWriter.FourCC.VP8, width, height, fps)
            ivf.writeHeader()
            out.flush()

            sink = ClientSink(
                socket = sock,
                out = out,
                ivf = ivf,
                sawKeyframe = false,
                framesSinceFlush = 0
            )

            // ---- NEW: we have a client, disable idle-stop deadline ----
            noClientDeadlineMs = Long.MAX_VALUE

            Log.i(TAG, "IVF header written for new client: VP80 ${width}x$height timebase=1/$fps")
            updateNotification("Streaming VP8 IVF (adb forward tcp:27183 localabstract:$ABSTRACT_NAME)")
            broadcastState(running = running.get(), reason = REASON_CLIENT_CONNECTED)
        }
    }

    private fun resetPerRun() {
        intervalFrames.set(0)
        intervalBytes.set(0)
        totalFrames.set(0)
        totalBytes.set(0)
        keyframes.set(0)
        droppedBeforeKeyframe.set(0)
        writeErrors.set(0)
        lastKeyframeAtMs.set(0)
        lastFrameBytes.set(0)
        statsLastTickMs = SystemClock.elapsedRealtime()

        keyframeTickerRunning = false
        statsTickerRunning = false
        idleStopTickerRunning = false
        noClientDeadlineMs = Long.MAX_VALUE

        synchronized(sinkLock) {
            try { sink?.socket?.close() } catch (_: Throwable) {}
            sink = null
        }
    }

    // ---- NEW: auto-stop if host disconnects and no reconnect for STOP_IF_NO_CLIENT_MS ----
    private fun startIdleStopTicker() {
        val h = codecHandler ?: return
        if (idleStopTickerRunning) return
        idleStopTickerRunning = true

        h.post(object : Runnable {
            override fun run() {
                if (!running.get() || !idleStopTickerRunning) return

                val hasClient = synchronized(sinkLock) { sink != null }
                if (!hasClient) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= noClientDeadlineMs) {
                        Log.i(TAG, "No host client for ${STOP_IF_NO_CLIENT_MS}ms -> stopping service")
                        broadcastState(false, "idle_timeout")
                        running.set(false)
                        stopSelf()
                        return
                    }
                } else {
                    noClientDeadlineMs = Long.MAX_VALUE
                }

                h.postDelayed(this, 250L)
            }
        })
    }

    private fun startEncoder(width: Int, height: Int, fps: Int, bitrate: Int) {
        val mime = "video/x-vnd.on2.vp8"
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            try {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            } catch (_: Throwable) {}
        }

        val c = MediaCodec.createEncoderByType(mime)
        codec = c

        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (!running.get()) return
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
                    if (info.size <= 0) return

                    val buf = codec.getOutputBuffer(index) ?: return
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)

                    val frame = ByteArray(info.size)
                    buf.get(frame)

                    val isKeyframe = isVp8KeyframeForSize(frame, width, height)

                    val localSink: ClientSink? = synchronized(sinkLock) { sink }
                    if (localSink == null) return

                    if (!localSink.sawKeyframe) {
                        if (!isKeyframe) {
                            droppedBeforeKeyframe.incrementAndGet()
                            return
                        }
                        localSink.sawKeyframe = true
                        keyframes.incrementAndGet()
                        lastKeyframeAtMs.set(SystemClock.elapsedRealtime())
                        Log.i(TAG, "First VP8 validated keyframe -> streaming to client")
                    } else if (isKeyframe) {
                        keyframes.incrementAndGet()
                        lastKeyframeAtMs.set(SystemClock.elapsedRealtime())
                    }

                    localSink.ivf.writeFrame(frame)

                    lastFrameBytes.set(frame.size)
                    totalFrames.incrementAndGet()
                    totalBytes.addAndGet(frame.size.toLong())
                    intervalFrames.incrementAndGet()
                    intervalBytes.addAndGet(frame.size.toLong())

                    localSink.framesSinceFlush++
                    if (localSink.framesSinceFlush >= FLUSH_EVERY_N_FRAMES || isKeyframe) {
                        localSink.out.flush()
                        localSink.framesSinceFlush = 0
                    }

                } catch (t: Throwable) {
                    writeErrors.incrementAndGet()
                    Log.e(TAG, "Write failed; detaching client", t)
                    detachClient()
                } finally {
                    try { codec.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Codec error", e)
                running.set(false)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Output format changed: $format")
            }
        }, codecHandler)

        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()

        val proj = projection ?: throw IllegalStateException("MediaProjection is null")
        vd = proj.createVirtualDisplay(
            "booxstream",
            width,
            height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        c.start()
        Log.i(TAG, "Encoder started: $mime ${width}x$height @$fps bitrate=$bitrate")

        requestSyncFrameSafely()
        startKeyframeTicker()
    }

    private fun detachClient(notifyHostState: Boolean = true) {
        var hadClient = false
        synchronized(sinkLock) {
            hadClient = sink != null
            try { sink?.socket?.close() } catch (_: Throwable) {}
            sink = null
        }

        if (hadClient) {
            // ---- NEW: host disconnected; start idle-stop countdown ----
            noClientDeadlineMs = SystemClock.elapsedRealtime() + STOP_IF_NO_CLIENT_MS
            updateNotification("Waiting for host… (auto-stop in ${STOP_IF_NO_CLIENT_MS / 1000}s)")
            if (notifyHostState) {
                broadcastState(running = running.get(), reason = REASON_CLIENT_DISCONNECTED)
            }
        }
    }

    private fun isVp8KeyframeForSize(frame: ByteArray, expectW: Int, expectH: Int): Boolean {
        if (frame.size < 12) return false

        val maxOff = min(8, frame.size - 10)
        for (off in 0..maxOff) {
            val tag0 = frame[off].toInt() and 0xFF
            val isKeyframeTag = (tag0 and 0x01) == 0
            if (!isKeyframeTag) continue

            val b3 = frame[off + 3].toInt() and 0xFF
            val b4 = frame[off + 4].toInt() and 0xFF
            val b5 = frame[off + 5].toInt() and 0xFF
            if (!(b3 == 0x9D && b4 == 0x01 && b5 == 0x2A)) continue

            val w0 = frame[off + 6].toInt() and 0xFF
            val w1 = frame[off + 7].toInt() and 0xFF
            val h0 = frame[off + 8].toInt() and 0xFF
            val h1 = frame[off + 9].toInt() and 0xFF

            val w = (w0 or (w1 shl 8)) and 0x3FFF
            val h = (h0 or (h1 shl 8)) and 0x3FFF

            val wOk = (w == expectW) || (w == expectW - 1) || (w == expectW + 1)
            val hOk = (h == expectH) || (h == expectH - 1) || (h == expectH + 1)

            if (wOk && hOk) return true
        }
        return false
    }

    private fun requestSyncFrameSafely() {
        val c = codec ?: return
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(b)
            Log.i(TAG, "Requested sync frame")
        } catch (t: Throwable) {
            Log.w(TAG, "Keyframe request not supported (ignored)", t)
        }
    }

    private fun startKeyframeTicker() {
        val h = codecHandler ?: return
        if (keyframeTickerRunning) return
        keyframeTickerRunning = true

        h.post(object : Runnable {
            override fun run() {
                if (!running.get() || !keyframeTickerRunning) return
                requestSyncFrameSafely()
                h.postDelayed(this, REQUEST_SYNC_EVERY_MS)
            }
        })
    }

    private fun stopKeyframeTicker() {
        keyframeTickerRunning = false
    }

    private fun startStatsTicker() {
        val h = codecHandler ?: return
        if (statsTickerRunning) return
        statsTickerRunning = true
        statsLastTickMs = SystemClock.elapsedRealtime()

        h.post(object : Runnable {
            override fun run() {
                if (!running.get() || !statsTickerRunning) return

                val now = SystemClock.elapsedRealtime()
                val dtMs = max(1L, now - statsLastTickMs)
                statsLastTickMs = now

                val f = intervalFrames.getAndSet(0)
                val b = intervalBytes.getAndSet(0)

                val fpsOut = (f * 1000.0 / dtMs).toFloat()
                val kbpsOut = (b * 8.0 / dtMs).toFloat()

                val sinceKf = run {
                    val last = lastKeyframeAtMs.get()
                    if (last == 0L) Long.MAX_VALUE else (now - last)
                }

                sendBroadcast(Intent(ACTION_VP8_STATS).apply {
    setPackage(packageName)
    putExtra(EXTRA_STATS_FPS, fpsOut)
    putExtra(EXTRA_STATS_KBPS, kbpsOut)
    putExtra(EXTRA_STATS_FRAMES, totalFrames.get())
    putExtra(EXTRA_STATS_BYTES, totalBytes.get())
    putExtra(EXTRA_STATS_SINCE_KF_MS, sinceKf)
    putExtra(EXTRA_STATS_LAST_FRAME, lastFrameBytes.get())
    putExtra(EXTRA_STATS_DROPPED_PRE_KF, droppedBeforeKeyframe.get())
    putExtra(EXTRA_STATS_KEYFRAMES, keyframes.get())
    putExtra(EXTRA_STATS_ERRORS, writeErrors.get())
})

                h.postDelayed(this, 1000L)
            }
        })
    }

    private fun stopStatsTicker() {
        statsTickerRunning = false
    }

    private fun pickDefaultBitrate(w: Int, h: Int, fps: Int): Int {
        val pixelsPerSec = w.toLong() * h.toLong() * fps.toLong()
        val bpp = 0.16
        val raw = (pixelsPerSec * bpp).toLong()
        val clamped = raw.coerceIn(700_000L, 7_000_000L)
        return clamped.toInt()
    }

    private fun updateNotification(text: String) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= 33) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
    }

    nm.notify(NOTIF_ID, buildNotification(text))
}

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("BooxStream")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "BooxStream", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun stopEverything() {
        running.set(false)
        stopKeyframeTicker()
        stopStatsTicker()
        idleStopTickerRunning = false

        detachClient(notifyHostState = false)

        try { server?.close() } catch (_: Throwable) {}
        server = null

        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null

        try { vd?.release() } catch (_: Throwable) {}
        vd = null

        try { projection?.stop() } catch (_: Throwable) {}
        projection = null

        try { codecThread?.quitSafely() } catch (_: Throwable) {}
        codecThread = null
        codecHandler = null
    }

    override fun onDestroy() {
        // Publish stopped state for UI
        isRunning.set(false)
        broadcastState(false, "onDestroy")

        stopEverything()
        super.onDestroy()
    }
}
