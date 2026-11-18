package com.lowtube.android.data

/** Removes the known outer player bundle wrapper so named transforms can share its helper scope. */
internal object YoutubePlayerScriptScopeNormalizer {
    private const val BUNDLE_HEADER = "var _yt_player={};(function(g){var window=this;"
    private const val BUNDLE_FOOTER = "})(_yt_player);"
    private const val RUNTIME_HEADER = "var g={};var window=this;"

    fun prepare(source: String): String {
        if (!source.contains(BUNDLE_HEADER) || !source.contains(BUNDLE_FOOTER)) return source
        return source.replace(BUNDLE_HEADER, RUNTIME_HEADER).replace(BUNDLE_FOOTER, "")
    }
}
