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

package io.github.piyushdaiya.booxstream.codec

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodecProbeScreen(
    onBack: () -> Unit
) {
    var loading by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<CodecProbe.ProbeReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Codec Probe") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "This checks codecs as the app UID (more trustworthy than shell). " +
                        "We try create() and encoder configure() to reveal restrictions.",
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !loading,
                    onClick = {
                        loading = true
                        error = null
                        report = null
                        try {
                            report = CodecProbe.run()
                        } catch (t: Throwable) {
                            error = "${t.javaClass.simpleName}: ${t.message}"
                        } finally {
                            loading = false
                        }
                    }
                ) { Text(if (loading) "Running…" else "Run probe") }

                OutlinedButton(
                    enabled = report != null && !loading,
                    onClick = { report = null }
                ) { Text("Clear") }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            report?.let { rep ->
                SelectionContainer {
                    Text("Device: ${rep.device} (SDK ${rep.sdk})", style = MaterialTheme.typography.labelLarge)
                }

                Divider()

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(rep.results) { r ->
                        CodecRow(r)
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CodecRow(r: CodecProbe.ProbeResult) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${r.mime} • ${r.kind}", style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Text(r.name, style = MaterialTheme.typography.bodyMedium)
        }

        val caps = buildString {
            append("create="); append(if (r.createOk) "OK" else "FAIL")
            if (r.configureOk != null) {
                append("  configure="); append(if (r.configureOk == true) "OK" else "FAIL")
            }
            if (r.isVendor != null) append("  vendor=${r.isVendor}")
            if (r.isHardwareAccelerated != null) append("  hw=${r.isHardwareAccelerated}")
            if (r.isSoftwareOnly != null) append("  swOnly=${r.isSoftwareOnly}")
        }
        Text(caps, style = MaterialTheme.typography.bodySmall)

        r.error?.let {
            SelectionContainer { Text("error: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}