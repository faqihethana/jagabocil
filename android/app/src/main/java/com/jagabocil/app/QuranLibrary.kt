package com.jagabocil.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable internal data class QuranChapter(
    val id: String, val name: String, val verses: Int,
    val ayahs: List<String>? = null, val sha256: String? = null, val maxRecordingSeconds: Int = 180,
) {
    fun matches(query: String): Boolean =
        id == query.trim() || name.filter(Char::isLetterOrDigit)
            .contains(query.filter(Char::isLetterOrDigit), ignoreCase = true)
}

@Serializable internal data class QuranText(val id: String, val ayahs: List<String>, val notice: String)

@Serializable private data class QuranCatalog(
    val chapters: List<QuranChapter>, val notice: String,
)

internal class QuranLibrary(private val context: Context) {
    private val directory = File(context.filesDir, "quran-uthmani-v1")
    private val catalog by lazy {
        context.assets.open("quran-catalog.json").bufferedReader().use {
            Json.decodeFromString<QuranCatalog>(it.readText())
        }
    }
    val chapters get() = catalog.chapters
    val juz30 by lazy { chapters.filter { it.ayahs != null } }
    private val byId by lazy { chapters.associateBy { it.id } }

    fun chapter(id: String) = byId[id]
    fun name(id: String) = chapter(id)?.name ?: "Surah $id"

    private fun file(chapter: QuranChapter) = AtomicFile(File(directory, "${chapter.id}.json"))

    suspend fun downloaded() = withContext(Dispatchers.IO) {
        chapters.filter { it.ayahs == null && file(it).baseFile.isFile }.map { it.id }.toSet()
    }

    suspend fun read(chapter: QuranChapter): List<String>? {
        chapter.ayahs?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val text = Json.decodeFromString<QuranText>(file(chapter).readFully().decodeToString())
                text.ayahs.takeIf { valid(chapter, text) }
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                null
            }
        }
    }

    suspend fun download(chapter: QuranChapter, api: Api): List<String> {
        read(chapter)?.let { return it }
        val text = api.quranText(chapter.id)
        require(valid(chapter, text)) { "Teks surah tidak cocok dengan edisi di aplikasi. Coba lagi." }
        return withContext(Dispatchers.IO) {
            val target = file(chapter)
            val stream = target.startWrite()
            try {
                stream.write(Json.encodeToString(QuranText.serializer(), text).encodeToByteArray())
                target.finishWrite(stream)
            } catch (error: IOException) {
                target.failWrite(stream)
                throw error
            }
            if (read(chapter) == null) throw IOException("Unduhan belum tersimpan.")
            text.ayahs
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun valid(chapter: QuranChapter, text: QuranText): Boolean =
        chapter.id == text.id && text.ayahs.size == chapter.verses && text.notice == catalog.notice &&
            MessageDigest.getInstance("SHA-256").digest(text.ayahs.joinToString("\n").encodeToByteArray())
                .toHexString() == chapter.sha256
}
