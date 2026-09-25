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

data class WorkedRecord(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val category: DeviceCategory,
    val code: IrCode
)

data class CustomRemoteButton(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: IrCode
)

data class CustomRemote(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: DeviceCategory,
    val buttons: List<CustomRemoteButton>,
    val createdAt: Long = System.currentTimeMillis()
)

data class BackupSummary(
    val devices: Int,
    val learned: Int,
    val remotes: Int,
    val worked: Int
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

    fun loadRemotes(): MutableList<CustomRemote> {
        val raw = prefs.getString("custom_remotes", null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                remoteFromJson(array.getJSONObject(index))
            }
        }.getOrDefault(mutableListOf())
    }

    fun saveRemotes(remotes: List<CustomRemote>) {
        val array = JSONArray()
        remotes.forEach { array.put(remoteToJson(it)) }
        prefs.edit().putString("custom_remotes", array.toString()).apply()
    }

    fun addRemote(remote: CustomRemote) {
        val remotes = loadRemotes()
        remotes.removeAll { it.id == remote.id }
        remotes += remote
        saveRemotes(remotes)
    }

    fun removeRemote(id: String) {
        val remotes = loadRemotes()
        remotes.removeAll { it.id == id }
        saveRemotes(remotes)
    }

    fun loadWorked(): MutableList<WorkedRecord> {
        val raw = prefs.getString("worked_history", null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val o = array.getJSONObject(index)
                WorkedRecord(
                    id = o.getString("id"),
                    createdAt = o.getLong("createdAt"),
                    category = DeviceCategory.valueOf(o.getString("category")),
                    code = codeFromJson(o.getJSONObject("code"))
                )
            }
        }.getOrDefault(mutableListOf())
    }

    fun saveWorked(records: List<WorkedRecord>) {
        val array = JSONArray()
        records.take(50).forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("createdAt", record.createdAt)
                    .put("category", record.category.name)
                    .put("code", codeToJson(record.code))
            )
        }
        prefs.edit().putString("worked_history", array.toString()).apply()
    }

    fun addWorked(category: DeviceCategory, code: IrCode) {
        val records = loadWorked()
        records.removeAll { it.code.id == code.id }
        records.add(0, WorkedRecord(category = category, code = code))
        saveWorked(records)
    }

    fun clearWorked() {
        prefs.edit().remove("worked_history").apply()
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

        val remotes = JSONArray()
        loadRemotes().forEach { remotes.put(remoteToJson(it)) }

        val worked = JSONArray()
        loadWorked().forEach { record ->
            worked.put(
                JSONObject()
                    .put("id", record.id)
                    .put("createdAt", record.createdAt)
                    .put("category", record.category.name)
                    .put("code", codeToJson(record.code))
            )
        }

        root.put("format", "IRUniversalAndroidBackup")
        root.put("version", 1)
        root.put("devices", devices)
        root.put("learned", learned)
        root.put("remotes", remotes)
        root.put("worked", worked)
        return root.toString(2)
    }

    fun restoreBackup(raw: String): BackupSummary {
        val root = JSONObject(raw)
        require(root.optString("format") == "IRUniversalAndroidBackup") {
            "El archivo no es una copia de TVBGoneAndroid."
        }
        require(root.optInt("version", -1) == 1) {
            "Versión de copia no compatible."
        }

        val devicesArray = root.optJSONArray("devices") ?: JSONArray()
        val devices = MutableList(devicesArray.length()) { index ->
            val o = devicesArray.getJSONObject(index)
            SavedDevice(
                id = o.getString("id"),
                name = o.getString("name"),
                category = DeviceCategory.valueOf(o.getString("category")),
                code = codeFromJson(o.getJSONObject("code"))
            )
        }

        val learnedArray = root.optJSONArray("learned") ?: JSONArray()
        val learned = MutableList(learnedArray.length()) { index ->
            codeFromJson(learnedArray.getJSONObject(index))
        }

        val remoteArray = root.optJSONArray("remotes") ?: JSONArray()
        val remotes = MutableList(remoteArray.length()) { index ->
            remoteFromJson(remoteArray.getJSONObject(index))
        }

        val workedArray = root.optJSONArray("worked") ?: JSONArray()
        val worked = MutableList(workedArray.length()) { index ->
            val o = workedArray.getJSONObject(index)
            WorkedRecord(
                id = o.getString("id"),
                createdAt = o.getLong("createdAt"),
                category = DeviceCategory.valueOf(o.getString("category")),
                code = codeFromJson(o.getJSONObject("code"))
            )
        }

        saveDevices(devices)
        saveLearned(learned)
        saveRemotes(remotes)
        saveWorked(worked)

        return BackupSummary(
            devices = devices.size,
            learned = learned.size,
            remotes = remotes.size,
            worked = worked.size
        )
    }

    private fun remoteToJson(remote: CustomRemote): JSONObject {
        val buttons = JSONArray()
        remote.buttons.forEach { button ->
            buttons.put(
                JSONObject()
                    .put("id", button.id)
                    .put("name", button.name)
                    .put("code", codeToJson(button.code))
            )
        }
        return JSONObject()
            .put("id", remote.id)
            .put("name", remote.name)
            .put("category", remote.category.name)
            .put("createdAt", remote.createdAt)
            .put("buttons", buttons)
    }

    private fun remoteFromJson(o: JSONObject): CustomRemote {
        val buttonsArray = o.optJSONArray("buttons") ?: JSONArray()
        val buttons = List(buttonsArray.length()) { index ->
            val button = buttonsArray.getJSONObject(index)
            CustomRemoteButton(
                id = button.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = button.optString("name").ifBlank { "Botón" },
                code = codeFromJson(button.getJSONObject("code"))
            )
        }
        return CustomRemote(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name").ifBlank { "Mi mando" },
            category = DeviceCategory.valueOf(o.optString("category", DeviceCategory.TELEVISION.name)),
            buttons = buttons,
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
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
