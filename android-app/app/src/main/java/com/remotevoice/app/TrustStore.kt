package com.remotevoice.app

import android.content.SharedPreferences
import java.util.Locale

/**
 * Android 端内部 TOFU 仓库。
 * 指纹按 server 地址隔离，用户切换服务器时不会把旧服务器的证书带过去。
 */
object TrustStore {

    private const val KEY_FINGERPRINTS = "fingerprints"

    fun serverKey(host: String, port: Int): String =
        "${host.trim().lowercase(Locale.ROOT)}:$port"

    fun load(prefs: SharedPreferences, serverKey: String): String {
        val records = try {
            org.json.JSONObject(prefs.getString(KEY_FINGERPRINTS, "{}") ?: "{}")
        } catch (_: org.json.JSONException) {
            return ""
        }
        val value = records.optString(serverKey, "")
        return normalize(value).takeIf(::isValid) ?: ""
    }

    fun remember(prefs: SharedPreferences, serverKey: String, fingerprint: String) {
        val value = normalize(fingerprint)
        if (!isValid(value)) return
        val records = try {
            org.json.JSONObject(prefs.getString(KEY_FINGERPRINTS, "{}") ?: "{}")
        } catch (_: org.json.JSONException) {
            org.json.JSONObject()
        }
        records.put(serverKey, value)
        prefs.edit().putString(KEY_FINGERPRINTS, records.toString()).apply()
    }

    /** 用户主动清除某服务器的信任记录；下次连接重新 TOFU。 */
    fun clear(prefs: SharedPreferences, serverKey: String) {
        val records = try {
            org.json.JSONObject(prefs.getString(KEY_FINGERPRINTS, "{}") ?: "{}")
        } catch (_: org.json.JSONException) {
            return
        }
        records.remove(serverKey)
        prefs.edit().putString(KEY_FINGERPRINTS, records.toString()).apply()
    }

    private fun normalize(value: String): String =
        value.filter { !it.isWhitespace() && it != ':' && it != '-' }
            .lowercase(Locale.ROOT)

    private fun isValid(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
