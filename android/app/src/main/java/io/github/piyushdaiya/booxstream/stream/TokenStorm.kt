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