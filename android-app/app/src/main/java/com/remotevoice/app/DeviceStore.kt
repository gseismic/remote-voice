package com.remotevoice.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多设备存储（v2 多设备单激活）：
 * prefs[devices] = JSONArray [{id,name,secret,type}]，prefs[active_id] = id。
 * 手机侧保存秘密原文（需要原文连入，与 v1 token 同一存储策略）；
 * name 首次连接成功后由 AUTH_OK 回传的设备名自动补全，用户可改别名。
 */
class DeviceStore(context: Context) {

    data class Device(
        val id: String,
        var name: String,
        val secret: String,
        val type: String,  // "perm" / "temp"
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Device> {
        val arr = JSONArray(prefs.getString(KEY_DEVICES, "[]") ?: "[]")
        val out = ArrayList<Device>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Device(
                id = o.getString("id"),
                name = o.optString("name", ""),
                secret = o.getString("secret"),
                type = o.optString("type", "temp"),
            ))
        }
        return out
    }

    fun active(): Device? {
        val id = prefs.getString(KEY_ACTIVE_ID, "") ?: ""
        return list().firstOrNull { it.id == id } ?: list().firstOrNull()
    }

    fun activate(id: String): Boolean {
        if (list().none { it.id == id }) return false
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        return true
    }

    fun activeId(): String = prefs.getString(KEY_ACTIVE_ID, "") ?: ""

    /** 新增设备；alias 为空时待首次连接后用 Mac 回传名补全。 */
    fun add(alias: String, secret: String, type: String): String {
        val id = java.util.UUID.randomUUID().toString()
        val arr = JSONArray(prefs.getString(KEY_DEVICES, "[]") ?: "[]")
        arr.put(JSONObject().apply {
            put("id", id)
            put("name", alias)
            put("secret", secret)
            put("type", type)
        })
        prefs.edit()
            .putString(KEY_DEVICES, arr.toString())
            .putString(KEY_ACTIVE_ID, id)  // 新增即激活
            .apply()
        return id
    }

    fun remove(id: String) {
        val arr = JSONArray(prefs.getString(KEY_DEVICES, "[]") ?: "[]")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("id") != id) out.put(arr.getJSONObject(i))
        }
        val activeId = prefs.getString(KEY_ACTIVE_ID, "") ?: ""
        prefs.edit()
            .putString(KEY_DEVICES, out.toString())
            .apply {
                if (activeId == id) {
                    prefs.edit().putString(KEY_ACTIVE_ID, "").apply()
                }
            }
    }

    fun rename(id: String, name: String) {
        val arr = JSONArray(prefs.getString(KEY_DEVICES, "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("id") == id) {
                arr.getJSONObject(i).put("name", name)
            }
        }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    /** 更新名字与秘密（保持 id/激活状态不变）。 */
    fun update(id: String, name: String, secret: String, type: String) {
        val arr = JSONArray(prefs.getString(KEY_DEVICES, "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("id") == id) {
                val o = arr.getJSONObject(i)
                o.put("name", name)
                o.put("secret", secret)
                o.put("type", type)
            }
        }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    /** 首次连接成功后补全设备名（保留用户已设别名）。 */
    fun fillNameIfEmpty(id: String, remoteName: String) {
        val d = list().firstOrNull { it.id == id }
        if (d != null && d.name.isBlank()) rename(id, remoteName)
    }

    companion object {
        const val PREFS = "config"
        const val KEY_DEVICES = "devices"
        const val KEY_ACTIVE_ID = "active_id"
    }
}
