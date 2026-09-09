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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** UI state machine for the single capture screen. */
sealed interface UiState {
    /** No auth token stored yet — show the token entry screen. */
    data object NeedToken : UiState
    data object Idle : UiState
    data object Recording : UiState
    data object Uploading : UiState
    data object Success : UiState
    data class Error(val message: String) : UiState
}

class MainActivity : ComponentActivity() {

    private lateinit var tokenStore: TokenStore
    private lateinit var recorder: AudioRecorder
    private val uploader = CapsuleUploader()

    private var uiState by mutableStateOf<UiState>(UiState.Idle)
    private var hasMicPermission by mutableStateOf(false)
    private var uploadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenStore = TokenStore(this)
        recorder = AudioRecorder(this)
        recorder.onMaxDurationReached = { finishRecordingAndUpload() }

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
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSaveToken = { value ->
                        tokenStore.token = value
                        if (tokenStore.hasToken) uiState = UiState.Idle
                    },
                    onPressStart = ::startRecording,
                    onPressEnd = ::finishRecordingAndUpload,
                    onReset = { if (uiState !is UiState.Recording) uiState = UiState.Idle },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
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
            val outcome = uploader.upload(result.file, token)
            runCatching { result.file.delete() }
            uiState = when (outcome) {
                is CapsuleUploader.Outcome.Success -> UiState.Success
                is CapsuleUploader.Outcome.Failure ->
                    UiState.Error(getString(R.string.upload_failed))
            }
        }
    }

    private companion object {
        const val EXTRA_AUTH_TOKEN = "auth_token"
    }
}
