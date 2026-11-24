package com.remotevoice.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 目录条目（协议 v4 LIST 帧的手机侧模型，与 server MacInfo 对应）。
 * 同时用作主界面的 Mac 缓存（离线也可见）。
 */
data class MacInfo(
    val deviceId: String,
    val name: String,
    val online: Boolean,
    val busy: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("name", name)
        put("online", online)
        put("busy", busy)
    }

    companion object {
        fun fromJson(o: JSONObject) = MacInfo(
            deviceId = o.getString("device_id"),
            name = o.optString("name", ""),
            online = o.optBoolean("online", false),
            busy = o.optBoolean("busy", false),
        )
    }
}

/**
 * 服务器存储（协议 v4）：
 * 每个中继分别保存地址、传输模式、密码、控制目标与目录缓存。
 * prefs[active_server] 保存当前中继 ID。
 * 旧版（v3 per-Mac 秘密）数据在首次访问时一次性迁移：全局 server 地址 + 激活设备密码。
 */
class ServerStore(context: Context) {

    data class Server(
        val id: String,
        val host: String,
        val port: Int,
        var password: String,
        var mode: ConfigParser.TlsMode = ConfigParser.TlsMode.AUTO,
    ) {
        fun address(): String = ConfigParser.format(ConfigParser.Parsed(host, port, mode))
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacyIfNeeded()
        val id = activeId()
        if (id.isNotEmpty() && (prefs.contains(KEY_BRIDGE_TARGET) || prefs.contains(KEY_MAC_CACHE))) {
            val editor = prefs.edit()
            for (key in listOf(KEY_BRIDGE_TARGET, KEY_MAC_CACHE)) {
                val legacy = prefs.getString(key, null) ?: continue
                if (!prefs.contains("${key}_$id")) editor.putString("${key}_$id", legacy)
                editor.remove(key)
            }
            editor.apply()
        }
    }

    fun list(): List<Server> {
        val arr = JSONArray(prefs.getString(KEY_SERVERS, "[]") ?: "[]")
        val out = ArrayList<Server>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Server(
                    id = o.getString("id"),
                    host = o.getString("host"),
                    port = o.getInt("port"),
                    password = o.optString("password", ""),
                    mode = ConfigParser.TlsMode.entries.firstOrNull { it.name == o.optString("mode") }
                        ?: ConfigParser.TlsMode.AUTO,
                )
            )
        }
        return out
    }

    fun active(): Server? {
        val id = activeId()
        return list().firstOrNull { it.id == id } ?: list().firstOrNull()
    }

    fun activeId(): String = prefs.getString(KEY_ACTIVE_SERVER, "") ?: ""

    /** 新增或更新同一中继，HTTPS 和原生 TLS 在同地址上可独立保存。 */
    fun add(server: ConfigParser.Parsed, password: String, deviceId: String? = null) {
        val servers = list().toMutableList()
        val https = server.mode == ConfigParser.TlsMode.HTTPS
        val existing = servers.firstOrNull {
            it.host.equals(server.host, ignoreCase = true) && it.port == server.port &&
                (it.mode == ConfigParser.TlsMode.HTTPS) == https
        }
        val id: String
        if (existing != null) {
            existing.password = password
            existing.mode = server.mode
            id = existing.id
        } else {
            id = java.util.UUID.randomUUID().toString()
            servers.add(Server(id, server.host, server.port, password, server.mode))
        }
        val editor = prefs.edit().putServers(servers).putString(KEY_ACTIVE_SERVER, id)
        if (deviceId != null) editor.putString("${KEY_BRIDGE_TARGET}_$id", deviceId)
        editor.apply()
    }

    fun remove(id: String) {
        val out = list().filterNot { it.id == id }
        val editor = prefs.edit().putServers(out)
            .remove("${KEY_BRIDGE_TARGET}_$id")
            .remove("${KEY_MAC_CACHE}_$id")
        if (activeId() == id) {
            editor.putString(KEY_ACTIVE_SERVER, out.firstOrNull()?.id ?: "")
        }
        editor.apply()
    }

    fun updatePassword(id: String, password: String) {
        val servers = list().toMutableList()
        servers.firstOrNull { it.id == id }?.password = password
        prefs.edit().putServers(servers).apply()
    }

    // ---- Mac 目录缓存（LIST 轮询结果，主界面离线也可见）----

    fun target(serverId: String): String = prefs.getString("${KEY_BRIDGE_TARGET}_$serverId", "").orEmpty()

    fun selectMac(deviceId: String, serverId: String = activeId()) {
        if (serverId.isNotEmpty()) {
            prefs.edit().putString("${KEY_BRIDGE_TARGET}_$serverId", deviceId).apply()
        }
    }

    fun saveMacCache(macs: List<MacInfo>, serverId: String = activeId()) {
        val arr = JSONArray()
        macs.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("${KEY_MAC_CACHE}_$serverId", arr.toString()).apply()
    }

    fun macCache(serverId: String = activeId()): List<MacInfo> {
        val arr = JSONArray(prefs.getString("${KEY_MAC_CACHE}_$serverId", "[]") ?: "[]")
        val out = ArrayList<MacInfo>(arr.length())
        for (i in 0 until arr.length()) {
            runCatching { out.add(MacInfo.fromJson(arr.getJSONObject(i))) }
        }
        return out
    }

    private fun SharedPreferences.Editor.putServers(servers: List<Server>): SharedPreferences.Editor {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("host", s.host)
                put("port", s.port)
                put("password", s.password)
                put("mode", s.mode.name)
            })
        }
        return putString(KEY_SERVERS, arr.toString())
    }

    /** v3 → v4 一次性迁移：全局 server 地址 + 激活设备密码 → 服务器条目。 */
    private fun migrateLegacyIfNeeded() {
        if ((prefs.getString(KEY_SERVERS, null) ?: "").isNotBlank()) return
        val legacyServerRaw = prefs.getString("server", null)?.takeIf { it.isNotBlank() }
            ?: AudioStreamService.DEFAULT_SERVER
        val legacyDevices = runCatching {
            JSONArray(prefs.getString("devices", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val legacyActiveId = prefs.getString("active_id", "") ?: ""
        var password = ""
        for (i in 0 until legacyDevices.length()) {
            val d = legacyDevices.optJSONObject(i) ?: continue
            if (d.optString("id") == legacyActiveId || password.isEmpty()) {
                password = d.optString("secret", "")
            }
            if (d.optString("id") == legacyActiveId) break
        }
        val parsed = ConfigParser.parse(legacyServerRaw)
        if (parsed != null) {
            val id = java.util.UUID.randomUUID().toString()
            prefs.edit().putServers(listOf(Server(id, parsed.host, parsed.port, password, parsed.mode)))
                .putString(KEY_ACTIVE_SERVER, id).apply()
        }
    }

    companion object {
        const val PREFS = "config"
        private const val KEY_SERVERS = "servers"
        private const val KEY_ACTIVE_SERVER = "active_server"
        private const val KEY_MAC_CACHE = "mac_cache"
        private const val KEY_BRIDGE_TARGET = "bridge_target"
    }
}
