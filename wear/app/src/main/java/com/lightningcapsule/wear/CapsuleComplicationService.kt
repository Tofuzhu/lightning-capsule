package com.lightningcapsule.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Quick entry from the watch face: an icon-only complication that launches
 * [MainActivity] when tapped. Purely static — no dynamic data, no background
 * work, `updatePeriodMillis = 0` in the manifest.
 *
 * Supports the two icon complication types requested by watch faces:
 * [ComplicationType.MONOCHROMATIC_IMAGE] (tintable) and
 * [ComplicationType.SMALL_IMAGE] (`ICON` sub-type).
 */
class CapsuleComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        complicationData(request.complicationType)

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        complicationData(type)

    private fun complicationData(type: ComplicationType): ComplicationData? {
        val description = PlainComplicationText.Builder(
            getString(R.string.complication_description),
        ).build()
        val icon = Icon.createWithResource(this, R.drawable.ic_capsule)
        // SMALL_IMAGE (ICON) may be rendered untinted by the watch face, so it
        // uses a vivid, pre-colored variant instead of the plain white glyph.
        val vividIcon = Icon.createWithResource(this, R.drawable.ic_capsule_vivid)
        val tapAction = launchIntent()

        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage = MonochromaticImage.Builder(icon).build(),
                    contentDescription = description,
                ).setTapAction(tapAction).build()

            ComplicationType.SMALL_IMAGE ->
                SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder(vividIcon, SmallImageType.ICON).build(),
                    contentDescription = description,
                ).setTapAction(tapAction).build()

            // Watch faces only bind types listed in the manifest, so anything
            // else here is defensive: null means "no data for this slot".
            else -> null
        }
    }

    /**
     * Immutable [PendingIntent] that opens the capture screen. Holds no
     * references. No task flags — on Wear OS the system owns the launch task, and
     * flags like `NEW_TASK` / `CLEAR_TOP` break "app resume" from the watch face.
     */
    private fun launchIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
