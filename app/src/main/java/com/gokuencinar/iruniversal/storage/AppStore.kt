package com.gokuencinar.iruniversal.storage

import android.content.Context
import com.gokuencinar.iruniversal.ir.DeviceCategory
import com.gokuencinar.iruniversal.ir.IrCode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: DeviceCategory,
    val code: IrCode
)

class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("ir_universal_android", Context.MODE_PRIVATE)

    fun loadDevices(): MutableList<SavedDevice> {
        val raw = prefs.getString("saved_devices", null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val o = array.getJSONObject(index)
                SavedDevice(
                    o.getString("id"),
                    o.getString("name"),
                    DeviceCategory.valueOf(o.getString("category")),
                    codeFromJson(o.getJSONObject("code"))
                )
            }
        }.getOrDefault(mutableListOf())
    }

    fun saveDevices(devices: List<SavedDevice>) {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject()
                    .put("id", device.id)
                    .put("name", device.name)
                    .put("category", device.category.name)
                    .put("code", codeToJson(device.code))
            )
        }
        prefs.edit().putString("saved_devices", array.toString()).apply()
    }

    fun addDevice(device: SavedDevice) {
        val devices = loadDevices()
        devices.removeAll { it.id == device.id }
        devices += device
        saveDevices(devices)
    }

    fun loadLearned(): MutableList<IrCode> {
        val raw = prefs.getString("learned_codes", null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { codeFromJson(array.getJSONObject(it)) }
        }.getOrDefault(mutableListOf())
    }

    fun saveLearned(codes: List<IrCode>) {
        val array = JSONArray()
        codes.forEach { array.put(codeToJson(it)) }
        prefs.edit().putString("learned_codes", array.toString()).apply()
    }

    fun exportBackup(): String {
        val root = JSONObject()
        val devices = JSONArray()
        loadDevices().forEach { device ->
            devices.put(
                JSONObject()
                    .put("id", device.id)
                    .put("name", device.name)
                    .put("category", device.category.name)
                    .put("code", codeToJson(device.code))
            )
        }
        val learned = JSONArray()
        loadLearned().forEach { learned.put(codeToJson(it)) }
        root.put("format", "IRUniversalAndroidBackup")
        root.put("version", 1)
        root.put("devices", devices)
        root.put("learned", learned)
        return root.toString(2)
    }

    private fun codeToJson(code: IrCode): JSONObject {
        val durations = JSONArray()
        code.durationsMicros.forEach { durations.put(it) }
        return JSONObject()
            .put("id", code.id)
            .put("carrierHz", code.carrierHz)
            .put("durationsMicros", durations)
    }

    private fun codeFromJson(o: JSONObject): IrCode {
        val durations = o.getJSONArray("durationsMicros")
        return IrCode(
            o.getString("id"),
            o.getInt("carrierHz"),
            List(durations.length()) { durations.getInt(it) }
        )
    }
}
