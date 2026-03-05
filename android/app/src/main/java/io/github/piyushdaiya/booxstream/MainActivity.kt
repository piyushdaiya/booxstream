@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.piyushdaiya.booxstream

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.piyushdaiya.booxstream.codec.CodecProbeScreen
import io.github.piyushdaiya.booxstream.stream.Vp8IvfStreamService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat

data class Vp8Stats(
    val fps: Float = 0f,
    val kbps: Float = 0f,
    val frames: Long = 0,
    val bytes: Long = 0,
    val sinceKeyframeMs: Long = Long.MAX_VALUE,
    val lastFrameBytes: Int = 0,
    val droppedPreKf: Long = 0,
    val keyframes: Long = 0,
    val errors: Int = 0,
)

object Vp8StatsStore {
    private val _flow = MutableStateFlow<Vp8Stats?>(null)
    val flow = _flow.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _lastStopReason = MutableStateFlow<String?>(null)
    val lastStopReason = _lastStopReason.asStateFlow()

    private val _hostConnected = MutableStateFlow(false)
    val hostConnected = _hostConnected.asStateFlow()

    private val _lastHostEventReason = MutableStateFlow<String?>(null)
    val lastHostEventReason = _lastHostEventReason.asStateFlow()

    fun update(s: Vp8Stats?) = _flow.update { s }
    fun updateRunning(r: Boolean) = _running.update { r }
    fun updateStopReason(reason: String?) = _lastStopReason.update { reason }
    fun updateHostConnected(connected: Boolean) = _hostConnected.update { connected }
    fun updateLastHostEventReason(reason: String?) = _lastHostEventReason.update { reason }
}

private data class MirrorCfg(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 12,
    val bitrate: Int = 0 // 0 = auto on service
)

class MainActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_AUTOSTART = "booxstream_autostart"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_BITRATE = "bitrate"
        private const val DEBUG_COMMAND =
            "adb forward --remove tcp:27183 2>/dev/null; adb forward tcp:27183 localabstract:booxstream_ivf\n" +
                "ffplay -fflags nobuffer -flags low_delay -framedrop -f ivf -i tcp://127.0.0.1:27183"
    }

    private var receiver: BroadcastReceiver? = null

    private var cfgState by mutableStateOf(MirrorCfg())
    private var autostartNonce by mutableIntStateOf(0)

    private fun applyIntent(i: Intent?) {
        if (i == null) return

        val w = i.getIntExtra(EXTRA_WIDTH, cfgState.width)
        val h = i.getIntExtra(EXTRA_HEIGHT, cfgState.height)
        val fps = i.getIntExtra(EXTRA_FPS, cfgState.fps)
        val br = i.getIntExtra(EXTRA_BITRATE, cfgState.bitrate)

        cfgState = MirrorCfg(
            width = w.coerceIn(320, 2600),
            height = h.coerceIn(320, 2600),
            fps = fps.coerceIn(5, 60),
            bitrate = br
        )

        val auto = i.getBooleanExtra(EXTRA_AUTOSTART, false)
        if (auto) autostartNonce++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mpMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        applyIntent(intent)

        // fast UI init (before broadcasts arrive)
        val serviceRunning = Vp8IvfStreamService.isRunning.get()
        Vp8StatsStore.updateRunning(serviceRunning)
        Vp8StatsStore.updateHostConnected(false)
        Vp8StatsStore.updateLastHostEventReason(null)
        if (!serviceRunning) {
            Vp8StatsStore.update(null)
        }

        val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != Activity.RESULT_OK || res.data == null) {
                // user denied prompt; revert UI state
                Vp8StatsStore.updateRunning(false)
                Vp8StatsStore.updateHostConnected(false)
                Vp8StatsStore.updateLastHostEventReason(null)
                Vp8StatsStore.updateStopReason("permission_denied")
                return@registerForActivityResult
            }

            val i = Intent(this, Vp8IvfStreamService::class.java).apply {
                putExtra(Vp8IvfStreamService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(Vp8IvfStreamService.EXTRA_RESULT_DATA, res.data)
                putExtra(Vp8IvfStreamService.EXTRA_WIDTH, cfgState.width)
                putExtra(Vp8IvfStreamService.EXTRA_HEIGHT, cfgState.height)
                putExtra(Vp8IvfStreamService.EXTRA_FPS, cfgState.fps)
                putExtra(Vp8IvfStreamService.EXTRA_BITRATE, cfgState.bitrate)
            }
            startForegroundService(i)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Vp8IvfStreamService.ACTION_VP8_STATS -> {
                        val s = Vp8Stats(
                            fps = intent.getFloatExtra(Vp8IvfStreamService.EXTRA_STATS_FPS, 0f),
                            kbps = intent.getFloatExtra(Vp8IvfStreamService.EXTRA_STATS_KBPS, 0f),
                            frames = intent.getLongExtra(Vp8IvfStreamService.EXTRA_STATS_FRAMES, 0L),
                            bytes = intent.getLongExtra(Vp8IvfStreamService.EXTRA_STATS_BYTES, 0L),
                            sinceKeyframeMs = intent.getLongExtra(
                                Vp8IvfStreamService.EXTRA_STATS_SINCE_KF_MS,
                                Long.MAX_VALUE
                            ),
                            lastFrameBytes = intent.getIntExtra(Vp8IvfStreamService.EXTRA_STATS_LAST_FRAME, 0),
                            droppedPreKf = intent.getLongExtra(Vp8IvfStreamService.EXTRA_STATS_DROPPED_PRE_KF, 0L),
                            keyframes = intent.getLongExtra(Vp8IvfStreamService.EXTRA_STATS_KEYFRAMES, 0L),
                            errors = intent.getIntExtra(Vp8IvfStreamService.EXTRA_STATS_ERRORS, 0),
                        )
                        Vp8StatsStore.update(s)
                    }

                    Vp8IvfStreamService.ACTION_VP8_STATE -> {
                        val r = intent.getBooleanExtra(Vp8IvfStreamService.EXTRA_STATE_RUNNING, false)
                        val reason = intent.getStringExtra(Vp8IvfStreamService.EXTRA_STATE_REASON)

                        Vp8StatsStore.updateRunning(r)
                        when (reason) {
                            Vp8IvfStreamService.REASON_CLIENT_CONNECTED -> {
                                Vp8StatsStore.updateHostConnected(true)
                                Vp8StatsStore.updateLastHostEventReason(reason)
                            }

                            Vp8IvfStreamService.REASON_CLIENT_DISCONNECTED -> {
                                Vp8StatsStore.updateHostConnected(false)
                                Vp8StatsStore.updateLastHostEventReason(reason)
                            }
                        }

                        if (!r) {
                            Vp8StatsStore.updateHostConnected(false)
                            Vp8StatsStore.updateLastHostEventReason(reason)
                            Vp8StatsStore.update(null) // clear stats instead of zeroing them
                            Vp8StatsStore.updateStopReason(reason ?: "stopped")
                        } else {
                            Vp8StatsStore.updateStopReason(null)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Vp8IvfStreamService.ACTION_VP8_STATS)
            addAction(Vp8IvfStreamService.ACTION_VP8_STATE)
        }
        ContextCompat.registerReceiver(
    this,
    receiver,
    filter,
    ContextCompat.RECEIVER_NOT_EXPORTED
)
        // Android 13+ requires runtime permission to post notifications
        val notifPermLauncher = registerForActivityResult(RequestPermission()) { /* ignore result */ }

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            MaterialTheme {
                var showProbe by remember { mutableStateOf(false) }
                val stats by Vp8StatsStore.flow.collectAsState()
                val runningUi by Vp8StatsStore.running.collectAsState()
                val hostConnected by Vp8StatsStore.hostConnected.collectAsState()
                val stopReason by Vp8StatsStore.lastStopReason.collectAsState()
                val context = LocalContext.current

                LaunchedEffect(autostartNonce) {
                    if (autostartNonce > 0) {
                        // optimistic UI (reduces “button highlighted” confusion)
                        Vp8StatsStore.updateRunning(true)
                        Vp8StatsStore.updateHostConnected(false)
                        Vp8StatsStore.updateLastHostEventReason(null)
                        Vp8StatsStore.updateStopReason(null)
                        launcher.launch(mpMgr.createScreenCaptureIntent())
                    }
                }

                if (showProbe) {
                    CodecProbeScreen(onBack = { showProbe = false })
                } else {
                    Scaffold(
                        topBar = { TopAppBar(title = { Text("BooxStream") }) }
                    ) { padding ->
                        Column(
                            Modifier
                                .padding(padding)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Mirroring", style = MaterialTheme.typography.titleMedium)

                            Text(
                                if (runningUi) "Status: Running" else "Status: Stopped",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                when {
                                    !runningUi -> "Host: —"
                                    hostConnected -> "Host: Connected"
                                    else -> "Host: Waiting…"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (!runningUi && stopReason != null) {
                                Text("Last stop: $stopReason", style = MaterialTheme.typography.bodySmall)
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    enabled = !runningUi,
                                    onClick = {
                                        // optimistic UI: disable Start immediately
                                        Vp8StatsStore.updateRunning(true)
                                        Vp8StatsStore.updateHostConnected(false)
                                        Vp8StatsStore.updateLastHostEventReason(null)
                                        Vp8StatsStore.updateStopReason(null)
                                        launcher.launch(mpMgr.createScreenCaptureIntent())
                                    }
                                ) {
                                    Text("Start Mirroring")
                                }

                                OutlinedButton(
                                    enabled = runningUi,
                                    onClick = {
                                        // optimistic UI: reflect stop immediately
                                        Vp8StatsStore.updateRunning(false)
                                        Vp8StatsStore.updateHostConnected(false)
                                        Vp8StatsStore.updateLastHostEventReason(null)
                                        Vp8StatsStore.update(null)
                                        Vp8StatsStore.updateStopReason("user_stop")
                                        stopService(Intent(this@MainActivity, Vp8IvfStreamService::class.java))
                                    }
                                ) {
                                    Text("Stop")
                                }

                                OutlinedButton(onClick = { showProbe = true }) { Text("Advanced") }
                            }

                            Divider()

                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Streaming Stats", style = MaterialTheme.typography.titleMedium)

                                    if (!runningUi) {
                                        Text("Not streaming.")
                                    } else if (stats == null) {
                                        Text("Waiting for host to connect…")
                                    } else {
                                        val s = stats!!
                                        val sinceKfSec =
                                            if (s.sinceKeyframeMs == Long.MAX_VALUE) "∞"
                                            else "%.1f".format(s.sinceKeyframeMs / 1000.0)

                                        Text("fps: ${"%.1f".format(s.fps)}    kbps: ${"%.0f".format(s.kbps)}")
                                        Text("frames: ${s.frames}    bytes: ${s.bytes}")
                                        Text("keyframes: ${s.keyframes}    since kf: ${sinceKfSec}s")
                                        Text("last frame: ${s.lastFrameBytes}B")
                                        Text("dropped(pre-kf): ${s.droppedPreKf}    errors: ${s.errors}")
                                    }
                                }
                            }

                            Divider()

                            Text(
                                "Host tool will handle adb + playback/recording.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("booxstream_debug_command", DEBUG_COMMAND))
                                        Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Copy command")
                                }
                            }
                            SelectionContainer {
                                Text(
                                    "Example (for debugging only):\n$DEBUG_COMMAND",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onDestroy() {
        try {
            if (receiver != null) unregisterReceiver(receiver)
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}
