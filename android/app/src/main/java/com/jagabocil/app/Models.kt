package com.jagabocil.app

import kotlinx.serialization.Serializable

@Serializable data class Auth(val token: String, val role: String, val name: String)
@Serializable data class Surah(val id: String, val name: String, val verses: Int, val arabic: String)
@Serializable internal data class Story(
    val id: String, val title: String, val text: String,
    val theme: String = "", val templateId: String? = null,
)
@Serializable internal data class GeneratedStories(val books: List<Story>)
@Serializable internal data class GeneratedStory(val book: Story)
@Serializable data class BookReading(val title: String, val text: String)
@Serializable data class ReadingTask(
    val kind: String,
    val id: String = "",
    val surahId: String? = null,
    val firstAyah: Int? = null,
    val lastAyah: Int? = null,
    val title: String? = null,
    val text: String? = null,
) {
    val verseLabel get() = verseRangeLabel(firstAyah, lastAyah)
    internal fun title(quran: QuranLibrary) = title ?: quran.name(surahId.orEmpty())
    internal fun minSeconds(child: Child) = if (firstAyah != null) minOf(child.minSeconds, 5) else child.minSeconds
}
@Serializable internal data class ReadingTaskPlan(val tasks: List<ReadingTask>)
@Serializable data class TaskPermit(val recitationId: String, val expiresAt: Long)

private fun verseRangeLabel(first: Int?, last: Int?) = when {
    first == null || last == null -> null
    first == last -> "Ayat $first"
    else -> "Ayat $first–$last"
}
@Serializable data class Child(
    val id: String, val name: String, val mode: String, val surahId: String,
    val minSeconds: Int, val unlockMinutes: Int, val tvBrand: String, val aiConsent: Boolean,
    val tvTransport: String = "infrared", val tvMac: String = "",
    val tvPlatform: String = "wol", val tvHost: String = "",
    val username: String? = null, val tvRevision: Int? = null,
    val readingAssignment: String = "free", val bookReading: BookReading? = null,
    val readingTasks: List<ReadingTask> = emptyList(), val readingCycle: Int = 0,
) {
    val awaitsTv get() = tvTransport == "infrared" && tvBrand == "none"
}
internal fun Child.readingTaskTitle(quran: QuranLibrary): String = when (readingAssignment) {
    "quran" -> "Ngaji · ${quran.name(surahId)}"
    "book" -> bookReading?.let { "Baca buku · ${it.title}" } ?: "Baca buku"
    "plan" -> "${readingTasks.size} tugas · ${readingTasks.firstOrNull()?.title(quran).orEmpty()}"
    else -> "Anak pilih sendiri"
}
@Serializable data class Recitation(
    val id: String, val childId: String, val surahId: String, val status: String,
    val duration: Double, val createdAt: Long, val expiresAt: Long? = null,
    val usedAt: Long? = null, val feedback: String, val reviewer: String? = null,
    val readyAt: Long? = null, val reviewedAt: Long? = null,
    val readingKind: String = "quran", val bookTitle: String? = null, val hasTranscript: Boolean = false,
    val matchScore: Int? = null,
    val taskId: String? = null, val taskCycle: Int? = null,
    val firstAyah: Int? = null, val lastAyah: Int? = null,
) {
    fun canUse(now: Long) = status == "approved" && usedAt == null && (expiresAt ?: 0L) > now
}

internal fun Recitation.readingTitle(quran: QuranLibrary) =
    if (readingKind == "book") bookTitle ?: "Bacaan buku"
    else listOfNotNull(quran.name(surahId), verseRangeLabel(firstAyah, lastAyah)).joinToString(" · ")

@Serializable internal data class ReadingDetails(
    val transcript: String? = null, val referenceText: String? = null,
    val matchScore: Int? = null, val matchThreshold: Int, val vocalizedTranscript: String? = null,
)
@Serializable data class Dashboard(
    val role: String, val name: String, val aiAvailable: Boolean, val children: List<Child>,
    val recitations: List<Recitation>, val surahs: List<Surah>, val serverTime: Long,
    val recoveryConfigured: Boolean = false, val juz30Tasks: Boolean = false,
    val billing: Billing? = null,
    val readingActivities: Boolean = false,
    val bookLibrary: Boolean = false, val storyGeneration: Boolean = false,
    val readingAssignments: Boolean = false,
    val multiReadingTasks: Boolean = false, val activeTaskRecitations: List<Recitation> = emptyList(),
    val taskPermit: TaskPermit? = null,
)
@Serializable data class UploadedRecitation(val recitation: Recitation)
@Serializable data class Billing(val amount: Int, val days: Int, val expiresAt: Long?, val pending: Boolean)
@Serializable data class BillingResult(val billing: Billing, val checkoutUrl: String? = null)
@Serializable data class RecoveryCode(val code: String)
@Serializable internal data class UpdatedChild(val child: Child)
@Serializable internal data class TvSettings(
    val tvBrand: String,
    val tvTransport: String,
    val tvPlatform: String,
    val tvHost: String,
    val tvMac: String,
)
@Serializable internal data class PowerRequest(val recitationId: String, val tvTransport: String, val tvRevision: Int? = null)
@Serializable data class Health(val ok: Boolean, val aiAvailable: Boolean, val surahs: List<Surah>)
@Serializable data class Policy(
    val mode: String, val surahId: String, val minSeconds: Int, val unlockMinutes: Int,
    val tvBrand: String, val aiConsent: Boolean,
    val tvTransport: String = "infrared", val tvMac: String = "",
    val tvPlatform: String = "wol", val tvHost: String = "",
    val transcriptConsent: Boolean = false,
)
@Serializable data class Permit(val authorized: Boolean, val tvBrand: String, val expiresAt: Long,
    val tvTransport: String = "infrared", val tvMac: String = "",
    val tvPlatform: String = "wol", val tvHost: String = "")
