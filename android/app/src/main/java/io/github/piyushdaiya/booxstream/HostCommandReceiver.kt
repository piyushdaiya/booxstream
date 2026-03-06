package io.github.piyushdaiya.booxstream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class HostCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BooxStream.HostCommand"

        const val ACTION_SET_CONFIG = "io.github.piyushdaiya.booxstream.action.SET_CONFIG"
        const val ACTION_HOST_CONFIG_UPDATED =
            "io.github.piyushdaiya.booxstream.action.HOST_CONFIG_UPDATED"

        const val EXTRA_AUTOSTART = "io.github.piyushdaiya.booxstream.extra.AUTOSTART"
        const val EXTRA_WIDTH = "io.github.piyushdaiya.booxstream.extra.WIDTH"
        const val EXTRA_HEIGHT = "io.github.piyushdaiya.booxstream.extra.HEIGHT"
        const val EXTRA_FPS = "io.github.piyushdaiya.booxstream.extra.FPS"
        const val EXTRA_BITRATE = "io.github.piyushdaiya.booxstream.extra.BITRATE"

        private const val PREFS = "booxstream_host_config"
        private const val KEY_PENDING_AUTOSTART = "pending_autostart"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrate"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_CONFIG) return

        val autostart = intent.getBooleanExtra(EXTRA_AUTOSTART, false)
        val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
        val fps = intent.getIntExtra(EXTRA_FPS, 12)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 0)

        Log.i(
            TAG,
            "Received host config autostart=$autostart width=$width height=$height fps=$fps bitrate=$bitrate"
        )

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING_AUTOSTART, autostart)
            .putInt(KEY_WIDTH, width)
            .putInt(KEY_HEIGHT, height)
            .putInt(KEY_FPS, fps)
            .putInt(KEY_BITRATE, bitrate)
            .apply()

        val updated = Intent(ACTION_HOST_CONFIG_UPDATED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(updated)
    }
}