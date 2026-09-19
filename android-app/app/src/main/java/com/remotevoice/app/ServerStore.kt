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
 * prefs[servers] = JSONArray [{id,host,port,password}]，prefs[active_server] = id。
 * 一个服务器一个密码（手机认证用）；Mac 列表来自服务器 LIST（缓存见 [MacCache]）。
 * 旧版（v3 per-Mac 秘密）数据在首次访问时一次性迁移：全局 server 地址 + 激活设备密码。
 */
class ServerStore(context: Context) {

    data class Server(
        val id: String,
        val host: String,
        val port: Int,
        var password: String,
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacyIfNeeded()
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

    /** 新增或按 host:port 去重更新；返回 id 并设为激活。 */
    fun add(host: String, port: Int, password: String): String {
        val servers = list().toMutableList()
        val existing = servers.firstOrNull { it.host == host && it.port == port }
        val id: String
        if (existing != null) {
            existing.password = password
            id = existing.id
        } else {
            id = java.util.UUID.randomUUID().toString()
            servers.add(Server(id, host, port, password))
        }
        persist(servers)
        prefs.edit().putString(KEY_ACTIVE_SERVER, id).apply()
        return id
    }

    fun remove(id: String) {
        val out = list().filterNot { it.id == id }
        persist(out)
        if (activeId() == id) {
            prefs.edit().putString(KEY_ACTIVE_SERVER, out.firstOrNull()?.id ?: "").apply()
        }
    }

    fun updatePassword(id: String, password: String) {
        val servers = list().toMutableList()
        servers.firstOrNull { it.id == id }?.password = password
        persist(servers)
    }

    // ---- Mac 目录缓存（LIST 轮询结果，主界面离线也可见）----

    fun saveMacCache(macs: List<MacInfo>) {
        val arr = JSONArray()
        macs.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_MAC_CACHE, arr.toString()).apply()
    }

    fun macCache(): List<MacInfo> {
        val arr = JSONArray(prefs.getString(KEY_MAC_CACHE, "[]") ?: "[]")
        val out = ArrayList<MacInfo>(arr.length())
        for (i in 0 until arr.length()) {
            runCatching { out.add(MacInfo.fromJson(arr.getJSONObject(i))) }
        }
        return out
    }

    private fun persist(servers: List<Server>) {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("host", s.host)
                put("port", s.port)
                put("password", s.password)
            })
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
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
            persist(listOf(Server(id, parsed.host, parsed.port, password)))
            prefs.edit().putString(KEY_ACTIVE_SERVER, id).apply()
        }
    }

    companion object {
        const val PREFS = "config"
        private const val KEY_SERVERS = "servers"
        private const val KEY_ACTIVE_SERVER = "active_server"
        private const val KEY_MAC_CACHE = "mac_cache"
    }
}
