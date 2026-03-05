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

import android.content.Context
import java.security.SecureRandom

class TokenStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("booxstream", Context.MODE_PRIVATE)
    private val rng = SecureRandom()

    fun getOrCreateToken(): String {
        return prefs.getString("token", null) ?: rotateToken()
    }

    fun rotateToken(): String {
        val bytes = ByteArray(16)
        rng.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("token", token).apply()
        return token
    }
}