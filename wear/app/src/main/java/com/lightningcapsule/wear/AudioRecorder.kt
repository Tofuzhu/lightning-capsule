package com.lightningcapsule.wear

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Thin wrapper around [MediaRecorder] that produces an AAC-in-MPEG4 (.m4a) file,
 * which the Lightning Capsule backend (Whisper) accepts directly.
 *
 * All methods are expected to be called from the main thread. Recording itself
 * runs on a system thread inside MediaRecorder, so this does not block the UI.
 */
class AudioRecorder(private val context: Context) {

    /** Invoked on the main thread when the hard recording cap is hit. */
    var onMaxDurationReached: (() -> Unit)? = null

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtElapsedMs: Long = 0L

    val isRecording: Boolean
        get() = recorder != null

    /**
     * Starts recording into a fresh file in the app cache dir.
     * @return true if recording started, false if it could not be started.
     */
    fun start(): Boolean {
        if (recorder != null) return true

        val file = File(context.cacheDir, "capsule-${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(file.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        onMaxDurationReached?.invoke()
                    }
                }
                prepare()
                start()
            }
            recorder = rec
            outputFile = file
            startedAtElapsedMs = SystemClock.elapsedRealtime()
            true
        } catch (t: Throwable) {
            // Do not log the exception message verbatim — keep it generic.
            Log.w(TAG, "Failed to start recording")
            runCatching { rec.reset() }
            runCatching { rec.release() }
            runCatching { file.delete() }
            recorder = null
            outputFile = null
            false
        }
    }

    /**
     * Stops recording.
     * @return the recorded file, or null if nothing usable was produced.
     */
    fun stop(): Result? {
        val rec = recorder ?: return null
        val file = outputFile
        val durationMs = SystemClock.elapsedRealtime() - startedAtElapsedMs

        recorder = null
        outputFile = null

        val stopped = try {
            rec.stop()
            true
        } catch (t: Throwable) {
            // stop() throws if it is called before any data was captured.
            Log.w(TAG, "Recorder stop failed")
            false
        } finally {
            runCatching { rec.reset() }
            runCatching { rec.release() }
        }

        if (!stopped || file == null || !file.exists() || file.length() <= 0L) {
            file?.let { runCatching { it.delete() } }
            return null
        }
        return Result(file, durationMs)
    }

    /** Aborts recording and deletes any partial file. */
    fun cancel() {
        val rec = recorder ?: return
        val file = outputFile
        recorder = null
        outputFile = null
        runCatching { rec.stop() }
        runCatching { rec.reset() }
        runCatching { rec.release() }
        file?.let { runCatching { it.delete() } }
    }

    data class Result(val file: File, val durationMs: Long)

    companion object {
        const val MAX_DURATION_MS = 60_000
        const val MIN_DURATION_MS = 700L
        private const val TAG = "AudioRecorder"
    }
}
