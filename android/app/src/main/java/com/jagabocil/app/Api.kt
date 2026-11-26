package com.jagabocil.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal const val SERVER_URL = "https://jagabocil.onrender.com"

class ApiError(val status: Int, message: String) : Exception(message)
class Api(var token: String = "") {
    val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    fun body(vararg values: Pair<String, String>) = buildJsonObject { values.forEach { put(it.first, it.second) } }.toString()
        .toRequestBody("application/json".toMediaType())
    suspend fun request(
        path: String, method: String = "GET", body: RequestBody? = null, authenticated: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(SERVER_URL + path)
            .method(method, body ?: if (method in listOf("POST", "PUT")) "{}".toRequestBody("application/json".toMediaType()) else null)
        if (authenticated && token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiError(response.code,
                runCatching { json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() ?: "Tidak bisa terhubung (${response.code}).")
            text
        }
    }
    suspend fun dashboard() = json.decodeFromString<Dashboard>(request("/dashboard?quran=local&reading=extended"))
    internal suspend fun readingDetails(id: String) =
        json.decodeFromString<ReadingDetails>(request("/recitations/$id/reading-details"))
    internal suspend fun quranText(id: String) = json.decodeFromString<QuranText>(
        request("/quran/surahs/$id", authenticated = false),
    )
    internal suspend fun generatedStories() =
        json.decodeFromString<GeneratedStories>(request("/books/generated")).books
    internal suspend fun writeStory(templateId: String) =
        json.decodeFromString<GeneratedStory>(request("/books/generated", "POST", body("templateId" to templateId))).book
    suspend fun audio(id: String, file: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$SERVER_URL/recitations/$id/audio").header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use {
            if (!it.isSuccessful) throw ApiError(it.code, "Rekaman belum bisa diputar.")
            it.body?.byteStream()?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        }
    }
    suspend fun upload(file: File, surahId: String?, revision: Int?, bookId: String?): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (surahId != null) body.addFormDataPart("surahId", surahId)
        body.addFormDataPart("audio", "bacaan.m4a", file.asRequestBody("audio/mp4".toMediaType()))
        val path = buildString {
            append(if (surahId == null) "/recitations?kind=book" else "/recitations?selection=self")
            if (revision != null) append("&revision=$revision")
            if (bookId != null) append("&bookId=$bookId")
        }
        return request(path, "POST", body.build())
    }
}

// The bearer token is encrypted with a device-bound, non-exportable Android Keystore key.
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("jagabocil_session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("jagabocil_session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit {
            putString("token", Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP))
            putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString("server", SERVER_URL)
        }
    }
    fun token() = runCatching {
        // A session created against another server cannot be transferred.
        if (prefs.getString("server", SERVER_URL)?.trimEnd('/') != SERVER_URL) return@runCatching ""
        val encrypted = prefs.getString("token", null) ?: return@runCatching ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP))) }
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)))
    }.getOrDefault("")
    fun clear() { prefs.edit { remove("token"); remove("iv"); remove("server") } }
}
