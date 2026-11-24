package com.example.sensevault.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Each complete monitoring session is encrypted independently with AES-256-GCM. */
class SecureVault(context: Context) {
    private val entriesDir = File(context.applicationContext.filesDir, "encrypted_entries").apply { mkdirs() }

    @Synchronized
    fun save(note: String, recording: SensorRecording): VaultEntry {
        require(recording.samples.isNotEmpty()) { "No fresh sensor readings were collected in this session." }
        val entry = VaultEntry(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            note = note.trim(),
            startedAt = recording.startedAt,
            stoppedAt = recording.stoppedAt,
            sensors = recording.sensors,
            samples = recording.samples
        )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(toJson(entry).toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteBuffer.allocate(1 + iv.size + encrypted.size)
            .put(iv.size.toByte()).put(iv).put(encrypted).array()
        File(entriesDir, "${entry.createdAt}_${entry.id}.svault").writeBytes(payload)
        return entry
    }

    @Synchronized
    fun loadAll(): List<VaultEntry> = entriesDir.listFiles()
        ?.filter { it.isFile && it.extension == "svault" }
        ?.sortedByDescending { it.lastModified() }
        ?.mapNotNull { file ->
            try {
                val buffer = ByteBuffer.wrap(file.readBytes())
                if (!buffer.hasRemaining()) return@mapNotNull null
                val ivLength = buffer.get().toInt() and 0xff
                if (ivLength !in 12..16 || buffer.remaining() <= ivLength) return@mapNotNull null
                val iv = ByteArray(ivLength).also { buffer.get(it) }
                val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                fromJson(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            } catch (_: Exception) { null }
        } ?: emptyList()

    @Synchronized
    fun delete(entryId: String): Boolean = entriesDir.listFiles()
        ?.firstOrNull { it.name.endsWith("_${entryId}.svault") }?.delete() ?: false

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun toJson(entry: VaultEntry): String {
        val sensors = JSONArray()
        entry.sensors.forEach { s ->
            val values = JSONArray(); s.values.forEach { values.put(it) }
            sensors.put(JSONObject().put("sensorId", s.sensorId).put("name", s.name)
                .put("type", s.type).put("vendor", s.vendor).put("values", values)
                .put("unitLabel", s.unitLabel).put("timestampNs", s.timestampElapsedRealtimeNanos ?: JSONObject.NULL)
                .put("accuracy", s.accuracy ?: JSONObject.NULL).put("isFresh", s.isFresh))
        }
        val samples = JSONArray()
        entry.samples.forEach { sample ->
            val values = JSONArray(); sample.values.forEach { values.put(it) }
            samples.put(JSONObject().put("sensorId", sample.sensorId).put("sensorName", sample.sensorName)
                .put("sensorType", sample.sensorType).put("timestampNs", sample.timestampElapsedRealtimeNanos)
                .put("timestampMillis", sample.timestampWallClockMillis)
                .put("values", values).put("accuracy", sample.accuracy))
        }
        return JSONObject().put("id", entry.id).put("createdAt", entry.createdAt).put("note", entry.note)
            .put("startedAt", entry.startedAt).put("stoppedAt", entry.stoppedAt)
            .put("sensors", sensors).put("samples", samples).toString()
    }

    private fun fromJson(raw: String): VaultEntry {
        val json = JSONObject(raw)
        val sensorsJson = json.optJSONArray("sensors") ?: JSONArray()
        val sensors = buildList {
            for (i in 0 until sensorsJson.length()) {
                val s = sensorsJson.getJSONObject(i)
                val values = s.optJSONArray("values") ?: JSONArray()
                add(SensorSnapshot(s.optInt("sensorId"), s.optString("name"), s.optString("type"), s.optString("vendor"),
                    (0 until values.length()).map { values.optDouble(it).toFloat() }, s.optString("unitLabel"),
                    if (s.isNull("timestampNs")) null else s.optLong("timestampNs"),
                    if (s.isNull("accuracy")) null else s.optInt("accuracy"), s.optBoolean("isFresh")))
            }
        }
        val samplesJson = json.optJSONArray("samples") ?: JSONArray()
        val samples = buildList {
            for (i in 0 until samplesJson.length()) {
                val s = samplesJson.getJSONObject(i); val values = s.optJSONArray("values") ?: JSONArray()
                add(SensorSample(s.optInt("sensorId"), s.optString("sensorName"), s.optString("sensorType"),
                    s.optLong("timestampNs"), s.optLong("timestampMillis"),
                    (0 until values.length()).map { values.optDouble(it).toFloat() }, s.optInt("accuracy", -1)))
            }
        }
        return VaultEntry(json.getString("id"), json.getLong("createdAt"), json.optString("note"),
            json.optLong("startedAt"), json.optLong("stoppedAt"), sensors, samples)
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sensevault_entry_aes_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
