package com.jagabocil.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal const val REVIEW_RECORDING_EXTRA = "com.jagabocil.app.REVIEW_RECORDING"

internal data class ReviewNotice(val id: String, val at: Long, val kind: String) {
    val key get() = "$at:$id:$kind"
}

internal fun reviewNotices(data: Dashboard, after: Long): List<ReviewNotice> = data.recitations.mapNotNull { rec ->
    when {
        data.role == "parent" && rec.status == "pending" ->
            rec.readyAt?.takeIf { it > after }?.let { ReviewNotice(rec.id, it, "pending") }
        data.role == "child" && rec.status in listOf("approved", "rejected") ->
            rec.reviewedAt?.takeIf { it > after }?.let { ReviewNotice(rec.id, it, rec.status) }
        else -> null
    }
}

internal class ReviewNotifications(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("review_notifications", Context.MODE_PRIVATE)
    private val manager = NotificationManagerCompat.from(app)
    private val sessions = SessionStore(app)

    val enabled get() = prefs.getBoolean("enabled", false)
    fun permitted() = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun enable(data: Dashboard, token: String) = synchronized(lock) {
        seed(sessionKey(token), data.serverTime)
        prefs.edit { putBoolean("enabled", true) }
        schedule()
    }

    fun disable() = synchronized(lock) {
        prefs.edit { putBoolean("enabled", false) }
        clearSession()
    }

    fun clearSession() = synchronized(lock) {
        prefs.edit { remove("account"); remove("after"); remove("seen") }
        WorkManager.getInstance(app).cancelUniqueWork(CHANNEL)
        manager.cancelAll()
    }

    fun schedule() {
        if (!enabled || !permitted()) return
        val request = PeriodicWorkRequestBuilder<ReviewNotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(CHANNEL, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun observe(data: Dashboard, token: String) = synchronized(lock) {
        if (!enabled || !permitted() || token.isBlank() || sessions.token() != token) return@synchronized
        val account = sessionKey(token)
        if (prefs.getString("account", null) != account) {
            seed(account, data.serverTime)
            return@synchronized
        }
        val oldest = data.serverTime - TimeUnit.DAYS.toMillis(7)
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty().filter {
            (it.substringBefore(':').toLongOrNull() ?: 0L) >= oldest
        }.toMutableSet()
        val notices = reviewNotices(data, maxOf(oldest, prefs.getLong("after", data.serverTime)))
        for (notice in notices) {
            if (notice.key in seen) continue
            if (post(notice)) seen += notice.key
        }
        prefs.edit { putStringSet("seen", seen) }
    }

    private fun seed(account: String, after: Long) {
        prefs.edit { putString("account", account); putLong("after", after); remove("seen") }
    }

    private fun sessionKey(token: String) =
        MessageDigest.getInstance("SHA-256").digest("$SERVER_URL\n$token".toByteArray()).joinToString("") { "%02x".format(it) }

    @SuppressLint("MissingPermission")
    private fun post(notice: ReviewNotice): Boolean {
        if (!permitted()) return false
        val text = when (notice.kind) {
            "pending" -> "Ada rekaman bacaan baru untuk diperiksa."
            "approved" -> "Bacaanmu disetujui. Lihat hasilnya di JagaBocil."
            else -> "Bacaanmu perlu diulang. Lihat hasilnya di JagaBocil."
        }
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (notice.kind == "pending") {
            intent.data = Uri.Builder().scheme("jagabocil").authority("review").appendPath(notice.id).build()
            intent.putExtra(REVIEW_RECORDING_EXTRA, notice.id)
        }
        val openApp = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Rekaman dan hasil review", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("JagaBocil")
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        return try {
            manager.notify(notice.key, 1, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        const val CHANNEL = "recitation_reviews"
        val lock = Any()
    }
}

class ReviewNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val notifications = ReviewNotifications(applicationContext)
        val store = SessionStore(applicationContext)
        val token = store.token()
        if (token.isBlank() || !notifications.enabled || !notifications.permitted()) return Result.success()
        return try {
            notifications.observe(Api(token).dashboard(), token)
            Result.success()
        } catch (error: ApiError) {
            if (error.status == 429 || error.status >= 500) Result.retry() else Result.success()
        } catch (_: IOException) {
            Result.retry()
        }
    }
}
