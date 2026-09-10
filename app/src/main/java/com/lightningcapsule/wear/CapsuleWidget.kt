package com.lightningcapsule.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.remote.creation.compose.action.pendingIntentAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteImage
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteImageBitmap
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.wear.AssociateWithGlanceWearWidget
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.color
import androidx.glance.wear.core.WearWidgetParams
import androidx.wear.compose.remote.material3.RemoteColorScheme
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import androidx.wear.compose.remote.material3.RemoteText

/**
 * Wear OS 7 Widget (swipe-left card) that is a one-tap entry into the capture
 * screen. Both card sizes (SMALL 2x1 / LARGE 2x2, declared in
 * `res/xml/capsule_widget_info.xml`) render the same minimal content: the
 * capsule glyph above the "按住说话" label, the whole card tappable.
 *
 * No dynamic data / no refresh — the content is rebuilt only when the system
 * asks (e.g. widget added). Built with Jetpack Glance for Wear + Remote Compose;
 * Tiles is intentionally not used (sunset on Wear OS 7).
 */
@AssociateWithGlanceWearWidget(CapsuleWidget::class)
class CapsuleWidgetService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = CapsuleWidget()
}

class CapsuleWidget : GlanceWearWidget() {

    override suspend fun provideWidgetData(
        context: Context,
        params: WearWidgetParams,
    ): WearWidgetData {
        // Rasterise the vector glyph once, here (outside the RemoteComposable),
        // so the widget document only carries a bitmap.
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_capsule)!!
            .toBitmap(width = ICON_PX, height = ICON_PX)
            .asImageBitmap()
        val label = context.getString(R.string.hold_to_talk)
        val colorScheme = RemoteColorScheme()

        return WearWidgetDocument(
            background = WearWidgetBrush.color(colorScheme.surfaceContainer),
        ) {
            RemoteMaterialTheme(colorScheme = colorScheme) {
                CapsuleWidgetContent(icon, label)
            }
        }
    }

    private companion object {
        /** Rasterised glyph size; scaled down by [RemoteImage] to [ICON_DP]. */
        const val ICON_PX = 96
    }
}

@RemoteComposable
@Composable
private fun CapsuleWidgetContent(icon: ImageBitmap, label: String) {
    // Deferred: the PendingIntent is only materialised at serialization time.
    // No task flags — the system owns the launch task on Wear OS.
    val launch = pendingIntentAction { context ->
        PendingIntent.getActivity(
            context,
            /* requestCode = */ 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    RemoteColumn(
        modifier = RemoteModifier
            .fillMaxSize()
            .padding(PADDING_DP.rdp)
            .clickable(launch),
        verticalArrangement = RemoteArrangement.Center,
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
        RemoteImage(
            remoteBitmap = RemoteImageBitmap(icon),
            contentDescription = label.rs,
            modifier = RemoteModifier.size(ICON_DP.rdp),
        )
        RemoteText(
            text = label.rs,
            modifier = RemoteModifier.padding(top = LABEL_GAP_DP.rdp),
            color = RemoteMaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

private const val PADDING_DP = 8
private const val ICON_DP = 32
private const val LABEL_GAP_DP = 4
