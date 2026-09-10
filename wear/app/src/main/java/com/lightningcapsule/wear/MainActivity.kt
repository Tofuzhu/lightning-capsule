package com.lightningcapsule.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.MaterialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/** UI state machine for the single capture screen. */
sealed interface UiState {
    /** No auth token stored yet — show the token entry screen. */
    data object NeedToken : UiState
    data object Idle : UiState
    data object Recording : UiState
    data object Uploading : UiState
    data object Success : UiState
    /** Upload failed but the capsule was stashed in the offline queue. */
    data object Queued : UiState
    data class Error(val message: String) : UiState
}

class MainActivity : ComponentActivity() {

    private lateinit var tokenStore: TokenStore
    private lateinit var recorder: AudioRecorder
    private lateinit var queue: CaptureQueue
    private lateinit var networkMonitor: NetworkMonitor
    private val uploader = CapsuleUploader()

    /** Guards the drain loop so only one runs at a time. */
    private val drainMutex = Mutex()

    private var uiState by mutableStateOf<UiState>(UiState.Idle)
    private var hasMicPermission by mutableStateOf(false)
    private var pendingCount by mutableStateOf(0)
    private var draining by mutableStateOf(false)
    private var uploadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenStore = TokenStore(this)
        recorder = AudioRecorder(this)
        recorder.onMaxDurationReached = { finishRecordingAndUpload() }
        queue = CaptureQueue(File(filesDir, "capsule_queue"))
        pendingCount = queue.count()
        networkMonitor = NetworkMonitor(this) { runOnUiThread { maybeDrainQueue() } }

        // Dev convenience: `adb shell am start -n .../.MainActivity -e auth_token <TOKEN>`
        // lets the user seed the token without typing on the watch. Not logged.
        intent?.getStringExtra(EXTRA_AUTH_TOKEN)?.let { seeded ->
            tokenStore.token = seeded
            intent.removeExtra(EXTRA_AUTH_TOKEN)
        }

        hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        uiState = if (tokenStore.hasToken) UiState.Idle else UiState.NeedToken

        setContent {
            MaterialTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> hasMicPermission = granted }

                CaptureScreen(
                    state = uiState,
                    hasMicPermission = hasMicPermission,
                    pendingCount = pendingCount,
                    draining = draining,
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSaveToken = { value ->
                        tokenStore.token = value
                        if (tokenStore.hasToken) {
                            uiState = UiState.Idle
                            maybeDrainQueue()
                        }
                    },
                    onPressStart = ::startRecording,
                    onPressEnd = ::finishRecordingAndUpload,
                    onReset = { if (uiState !is UiState.Recording) uiState = UiState.Idle },
                    onRetryNow = ::maybeDrainQueue,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        networkMonitor.start()
        // Catch up on anything stashed while the app was away.
        maybeDrainQueue()
    }

    override fun onStop() {
        super.onStop()
        networkMonitor.stop()
        // Do not keep the mic open in the background.
        if (uiState is UiState.Recording) {
            recorder.cancel()
            uiState = UiState.Idle
        }
    }

    private fun startRecording() {
        if (!hasMicPermission) return
        if (uiState is UiState.Recording || uiState is UiState.Uploading) return
        if (!tokenStore.hasToken) {
            uiState = UiState.NeedToken
            return
        }
        uiState = if (recorder.start()) {
            UiState.Recording
        } else {
            UiState.Error(getString(R.string.record_failed))
        }
    }

    private fun finishRecordingAndUpload() {
        if (uiState !is UiState.Recording) return

        val result = recorder.stop()
        if (result == null || result.durationMs < AudioRecorder.MIN_DURATION_MS) {
            result?.file?.let { runCatching { it.delete() } }
            uiState = UiState.Error(getString(R.string.recording_too_short))
            return
        }

        val token = tokenStore.token
        if (token == null) {
            runCatching { result.file.delete() }
            uiState = UiState.NeedToken
            return
        }

        uiState = UiState.Uploading
        uploadJob?.cancel()
        uploadJob = lifecycleScope.launch {
            when (uploader.upload(result.file, token)) {
                is CapsuleUploader.Outcome.Success -> {
                    runCatching { result.file.delete() }
                    uiState = UiState.Success
                }

                is CapsuleUploader.Outcome.AuthError -> {
                    // Do not queue: retrying an invalid token just loops forever.
                    runCatching { result.file.delete() }
                    uiState = UiState.Error(getString(R.string.token_invalid))
                }

                is CapsuleUploader.Outcome.Failure -> {
                    val enq = withContext(Dispatchers.IO) { queue.enqueue(result.file) }
                    runCatching { result.file.delete() }
                    pendingCount = queue.count()
                    uiState = when (enq) {
                        is EnqueueResult.Enqueued -> UiState.Queued
                        is EnqueueResult.QueueFull -> UiState.Error(getString(R.string.queue_full))
                    }
                }
            }

            // Keep working through the backlog after a success or a fresh stash.
            if (uiState is UiState.Success || uiState is UiState.Queued) {
                maybeDrainQueue()
            }
        }
    }

    /**
     * Uploads queued capsules FIFO in the background until the queue is empty or
     * one fails. Never runs concurrently with itself. No-op without a token or an
     * empty queue.
     */
    private fun maybeDrainQueue() {
        val token = tokenStore.token ?: return
        if (queue.isEmpty) {
            pendingCount = 0
            return
        }

        lifecycleScope.launch {
            if (!drainMutex.tryLock()) return@launch
            draining = true
            try {
                withContext(Dispatchers.IO) {
                    while (isActive) {
                        val entry = queue.peek() ?: break
                        when (uploader.upload(queue.fileFor(entry), token)) {
                            is CapsuleUploader.Outcome.Success -> queue.remove(entry)

                            is CapsuleUploader.Outcome.AuthError -> {
                                withContext(Dispatchers.Main) {
                                    uiState = UiState.Error(getString(R.string.token_invalid))
                                }
                                break
                            }

                            is CapsuleUploader.Outcome.Failure -> {
                                queue.incrementAttempts(entry)
                                break
                            }
                        }
                        withContext(Dispatchers.Main) { pendingCount = queue.count() }
                    }
                }
            } finally {
                pendingCount = queue.count()
                draining = false
                drainMutex.unlock()
            }
        }
    }

    private companion object {
        const val EXTRA_AUTH_TOKEN = "auth_token"
    }
}
