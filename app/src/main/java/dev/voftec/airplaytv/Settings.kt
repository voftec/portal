package dev.voftec.airplaytv

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

object Settings {
    private const val PREFS = "airplaytv"
    private const val KEY_NAME = "device_name"
    private const val KEY_HWADDR = "hw_addr"
    private const val DEFAULT_NAME = "TV del cuarto"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceName(ctx: Context): String =
        prefs(ctx).getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME

    fun setDeviceName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_NAME, name.trim()).apply()
    }

    /** Locally-administered random MAC, generated once and persisted
     *  (Android doesn't expose the real one). */
    fun hwAddr(ctx: Context): ByteArray {
        val stored = prefs(ctx).getString(KEY_HWADDR, null)
        if (stored != null) {
            val parts = stored.split(":").map { it.toInt(16).toByte() }
            if (parts.size == 6) return parts.toByteArray()
        }
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = (mac[0].toInt() and 0xFE or 0x02).toByte()
        prefs(ctx).edit().putString(
            KEY_HWADDR, mac.joinToString(":") { "%02x".format(it) }
        ).apply()
        return mac
    }
}
