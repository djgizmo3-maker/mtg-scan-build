package com.mtgscanbuild

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ProLeaseCache(context: Context) {
    private val file = AtomicFile(java.io.File(context.noBackupFilesDir, "pro-lease"))
    private val alias = "mtg-verified-pro-lease"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing != null) return existing as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun load(uid: String, boot: Int, now: Long): Long? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size in 29..4096) { "Invalid entitlement cache size." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        val recordedAt = data.getLong("recordedAt")
        val deadline = data.getLong("deadline")
        return if (data.getString("uid") == uid && data.getInt("boot") == boot &&
            now >= recordedAt && deadline > now && deadline - recordedAt <= com.mtgscanbuild.data.PRO_OFFLINE_WINDOW_MS
        ) deadline else null
    }

    fun save(uid: String, boot: Int, now: Long, deadline: Long) {
        val data = JSONObject().put("uid", uid).put("boot", boot)
            .put("recordedAt", now).put("deadline", deadline).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(data)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: java.io.IOException) {
            file.failWrite(output)
            throw error
        }
    }

    fun clear() {
        file.delete()
        check(!file.baseFile.exists()) { "Could not clear entitlement cache." }
    }
}
