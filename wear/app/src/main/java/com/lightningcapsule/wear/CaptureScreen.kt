package com.lightningcapsule.wear

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.ripple
import kotlinx.coroutines.delay

/** Full-screen capture UI. All state is hoisted to the caller. */
@Composable
fun CaptureScreen(
    state: UiState,
    hasMicPermission: Boolean,
    pendingCount: Int,
    draining: Boolean,
    onRequestPermission: () -> Unit,
    onSaveToken: (String) -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onReset: () -> Unit,
    onRetryNow: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Standard Wear layout element: the curved clock at the top arc. It
        // positions itself on the top edge and does not consume space from the
        // centred content below, so the immersive push-to-talk layout is intact.
        TimeText()

        when {
            state is UiState.NeedToken -> TokenEntry(onSaveToken)
            !hasMicPermission -> PermissionPrompt(onRequestPermission)
            else -> CaptureContent(
                state = state,
                pendingCount = pendingCount,
                draining = draining,
                onPressStart = onPressStart,
                onPressEnd = onPressEnd,
                onReset = onReset,
                onRetryNow = onRetryNow,
            )
        }
    }
}

@Composable
private fun CaptureContent(
    state: UiState,
    pendingCount: Int,
    draining: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onReset: () -> Unit,
    onRetryNow: () -> Unit,
) {
    // Auto-return to Idle a moment after a success or an offline stash.
    LaunchedEffect(state) {
        if (state is UiState.Success || state is UiState.Queued) {
            delay(1800)
            onReset()
        }
    }

    val scheme = MaterialTheme.colorScheme
    // Recording keeps the red "danger/live" semantic via the theme's `error`
    // slot; every other colour is a plain theme slot so the whole screen tracks
    // the system dynamic palette.
    val circleColor = when (state) {
        is UiState.Recording -> scheme.error
        is UiState.Success -> scheme.primary
        else -> scheme.primaryContainer
    }
    val onCircleColor = when (state) {
        is UiState.Recording -> scheme.onError
        is UiState.Success -> scheme.onPrimary
        else -> scheme.onPrimaryContainer
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TalkButton(
            color = circleColor,
            description = stringResource(R.string.talk_button_desc),
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
        ) {
            when (state) {
                is UiState.Recording -> Text(
                    text = stringResource(R.string.release_to_send),
                    color = onCircleColor,
                    fontSize = 15.sp,
                )

                is UiState.Uploading -> CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    colors = ProgressIndicatorDefaults.colors(
                        indicatorColor = onCircleColor,
                        trackColor = onCircleColor.copy(alpha = 0.24f),
                    ),
                )

                is UiState.Success -> Text(
                    text = "✓",
                    color = onCircleColor,
                    fontSize = 40.sp,
                )

                is UiState.Queued -> Text(
                    text = stringResource(R.string.queued_short),
                    color = onCircleColor,
                    fontSize = 15.sp,
                )

                else -> Text(
                    text = stringResource(R.string.hold_to_talk),
                    color = onCircleColor,
                    fontSize = 16.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        val status: String? = when (state) {
            is UiState.Recording -> stringResource(R.string.recording)
            is UiState.Uploading -> stringResource(R.string.uploading)
            is UiState.Success -> stringResource(R.string.recorded)
            is UiState.Queued -> stringResource(R.string.queued_offline)
            is UiState.Error -> state.message
            else -> null
        }
        val statusColor = when (state) {
            is UiState.Error -> scheme.error
            is UiState.Queued -> scheme.primary
            else -> scheme.onSurfaceVariant
        }
        if (status != null) {
            Text(
                text = status,
                color = statusColor,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (state is UiState.Error) {
            Text(
                text = stringResource(R.string.hold_to_retry),
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }

        if (pendingCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (draining) {
                    stringResource(R.string.draining_count, pendingCount)
                } else {
                    stringResource(R.string.pending_count, pendingCount)
                },
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            if (!draining) {
                TextButton(
                    onClick = onRetryNow,
                    colors = TextButtonDefaults.textButtonColors(
                        contentColor = scheme.primary,
                    ),
                ) {
                    Text(text = stringResource(R.string.retry_now), fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * The signature 132dp push-to-talk control. Kept custom-drawn (a clipped,
 * bordered [Box]) because the circular press gesture is the app's core
 * interaction, but every colour now comes from the theme and it gains a bounded
 * ripple + a small press-scale for tactile feedback.
 */
@Composable
private fun TalkButton(
    color: Color,
    description: String,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "talkScale")
    Box(
        modifier = Modifier
            .size(132.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .indication(interactionSource, ripple())
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val press = PressInteraction.Press(it)
                        interactionSource.emit(press)
                        onPressStart()
                        val completed = tryAwaitRelease()
                        interactionSource.emit(
                            if (completed) {
                                PressInteraction.Release(press)
                            } else {
                                PressInteraction.Cancel(press)
                            },
                        )
                        onPressEnd()
                    },
                )
            },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun PermissionPrompt(onRequestPermission: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.need_mic_permission),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRequestPermission) {
            Text(text = stringResource(R.string.grant_permission))
        }
    }
}

@Composable
private fun TokenEntry(onSaveToken: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.token_title),
            color = scheme.onBackground,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        // Wear Material3 has no standard text field, so the BasicTextField
        // container stays hand-rolled — only its colours are themed.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainer)
                .border(1.dp, scheme.outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSaveToken(value) }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.token_hint),
                            color = scheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { onSaveToken(value) }) {
            Text(text = stringResource(R.string.save))
        }
    }
}
