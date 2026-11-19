package com.lowtube.android.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.lowtube.android.model.YouTubeCaptionTrack

/** Converts ephemeral resolver results into Media3 text tracks without persisting signed caption URLs. */
internal fun youtubeCaptionConfigurations(tracks: List<YouTubeCaptionTrack>): List<MediaItem.SubtitleConfiguration> =
    tracks.map { track ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.uri))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(track.languageCode)
            .setLabel(track.label)
            .build()
    }
