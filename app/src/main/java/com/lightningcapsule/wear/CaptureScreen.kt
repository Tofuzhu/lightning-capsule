package com.lightningcapsule.wear

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay

private val ScreenBackground = Color(0xFF000000)
private val IdleCircle = Color(0xFF2A2A2E)
private val RecordingCircle = Color(0xFFD32F2F)
private val UploadingCircle = Color(0xFF2A2A2E)
private val SuccessCircle = Color(0xFF2E7D32)
private val Accent = Color(0xFFFFD54A)
private val OnDark = Color(0xFFF2F2F2)
private val Muted = Color(0xFFB0B0B0)

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
            .background(ScreenBackground)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
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

    val circleColor = when (state) {
        is UiState.Recording -> RecordingCircle
        is UiState.Uploading -> UploadingCircle
        is UiState.Success -> SuccessCircle
        else -> IdleCircle
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TalkButton(
            color = circleColor,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
        ) {
            when (state) {
                is UiState.Recording -> Text(
                    text = stringResource(R.string.release_to_send),
                    color = Color.White,
                    fontSize = 15.sp,
                )

                is UiState.Uploading -> Spinner()

                is UiState.Success -> Text(
                    text = "✓",
                    color = Color.White,
                    fontSize = 40.sp,
                )

                is UiState.Queued -> Text(
                    text = stringResource(R.string.queued_short),
                    color = OnDark,
                    fontSize = 15.sp,
                )

                else -> Text(
                    text = stringResource(R.string.hold_to_talk),
                    color = OnDark,
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
        val isNotice = state is UiState.Error || state is UiState.Queued
        if (status != null) {
            Text(
                text = status,
                color = if (isNotice) Accent else Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (state is UiState.Error) {
            Text(
                text = stringResource(R.string.hold_to_retry),
                color = Muted,
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
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            if (!draining) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.retry_now),
                    color = Accent,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onRetryNow() })
                        }
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TalkButton(
    color: Color,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(132.dp)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, Color(0x33FFFFFF), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        tryAwaitRelease()
                        onPressEnd()
                    },
                )
            },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun Spinner() {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )
    Canvas(modifier = Modifier.size(40.dp)) {
        drawArc(
            color = Color.White,
            startAngle = angle,
            sweepAngle = 100f,
            useCenter = false,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun PermissionPrompt(onRequestPermission: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.need_mic_permission),
            color = OnDark,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        PillButton(text = stringResource(R.string.grant_permission), onClick = onRequestPermission)
    }
}

@Composable
private fun TokenEntry(onSaveToken: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.token_title),
            color = OnDark,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1C1C20))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = OnDark, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSaveToken(value) }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.token_hint),
                            color = Muted,
                            fontSize = 14.sp,
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        PillButton(text = stringResource(R.string.save), onClick = { onSaveToken(value) })
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Accent)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = Color(0xFF1B1B1F), fontSize = 14.sp)
    }
}
