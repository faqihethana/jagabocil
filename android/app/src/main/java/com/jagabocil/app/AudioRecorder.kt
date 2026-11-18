package com.jagabocil.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var file: File? = null; private set
    fun start(maxSeconds: Int) {
        discard()
        val target = File.createTempFile("ngaji-", ".m4a", context.cacheDir)
        file = target
        @Suppress("DEPRECATION")
        val audio = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = audio
        try {
            audio.setAudioSource(MediaRecorder.AudioSource.MIC)
            audio.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            audio.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            audio.setAudioSamplingRate(44100)
            audio.setAudioEncodingBitRate(96000)
            audio.setOutputFile(target.absolutePath)
            audio.setMaxDuration(maxSeconds * 1000)
            audio.prepare(); audio.start()
        } catch (e: Exception) { discard(); throw e }
    }
    fun stop(): File {
        val audio = recorder ?: error("Belum ada rekaman.")
        try { audio.stop() } catch (e: Exception) { file?.delete(); file = null; throw e }
        finally { audio.release(); recorder = null }
        return file ?: error("Rekaman tidak ditemukan.")
    }
    fun discard() {
        runCatching { recorder?.reset(); recorder?.release() }
        recorder = null
        file?.delete(); file = null
    }
}
