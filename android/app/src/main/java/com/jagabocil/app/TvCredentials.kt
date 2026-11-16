package com.jagabocil.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class TvCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("tv_credentials", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("jagabocil_tv", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("jagabocil_tv", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun id(c: Child) = MessageDigest.getInstance("SHA-256").digest("${c.id}|${c.tvPlatform}|${c.tvHost}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun get(c: Child): JSONObject? = runCatching {
        val data = prefs.getString(id(c), null) ?: return null
        val parts = data.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))) }
        JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))))
    }.getOrNull()
    fun save(c: Child, data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(data.toString().toByteArray())
        prefs.edit { putString(id(c), Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)) }
    }
    fun forget(c: Child) { prefs.edit { remove(id(c)) } }
    fun clear() { prefs.edit { clear() } }
}
