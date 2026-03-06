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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.piyushdaiya.booxstream.R
import io.github.piyushdaiya.booxstream.core.Protocol
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamService : Service() {

    companion object {
        const val EXTRA_TOKEN = "token"
        private const val TAG = "BooxStream"
        private const val CHANNEL_ID = "booxstream"
        private const val NOTIF_ID = 27183
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_TOKEN)?.encodeToByteArray()
        if (token == null || token.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        running = true
        executor.execute { runServer(token) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.SECONDS)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runServer(token: ByteArray) {
        try {
            // Bind localhost only.
            server = ServerSocket(Protocol.PORT, 1, InetAddress.getByName("127.0.0.1"))
            Log.i(TAG, "Listening on 127.0.0.1:${Protocol.PORT}")

            while (running) {
                val sock = server?.accept() ?: break
                handleClient(sock, token)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
        } finally {
            running = false
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun handleClient(sock: Socket, token: ByteArray) {
        sock.use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 5000

            val input = s.getInputStream()
            val output = s.getOutputStream()

            // Auth handshake
            val hello = try {
                Protocol.readHello(input)
            } catch (e: Exception) {
                Log.w(TAG, "Bad HELLO: ${e.message}")
                return
            }

            if (!hello.token.contentEquals(token)) {
                Log.w(TAG, "Auth failed")
                return
            }

            // Reply OK (dummy values for now)
            Protocol.writeHelloOk(
                output,
                Protocol.HelloOk(
                    codec = Protocol.CodecId.VP8,
                    width = 0u,
                    height = 0u,
                    timebaseDen = Protocol.TIMEBASE_DEN_US
                )
            )

            // For now: do nothing further (no streaming yet).
            // Next step: encode to VP8 IVF and stream bytes.
            Log.i(TAG, "Handshake OK, client connected")
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BooxStream",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BooxStream running")
            .setContentText("Listening on localhost:${Protocol.PORT}")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()
    }
}