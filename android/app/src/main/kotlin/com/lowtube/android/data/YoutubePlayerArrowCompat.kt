package com.lowtube.android.data

/** Converts a narrowly supported named expression-body arrow declaration to ES5 for old WebViews. */
internal object YoutubePlayerArrowCompat {
    private val identifier = Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,127}$")

    fun normalize(source: String, functionName: String): String {
        if (!identifier.matches(functionName) || source.length > MAX_SOURCE_LENGTH) return source
        val name = Regex.escape(functionName)
        val declaration = Regex(
            "\\b(?:var|let|const)\\s+($name)\\s*=\\s*(?:\\(([^()]{0,256})\\)|([A-Za-z_$][A-Za-z0-9_$]{0,127}))\\s*=>\\s*([^;{}]{1,2048});",
        )
        val match = declaration.find(source) ?: return source
        val parameters = match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
        val expression = match.groups[4]?.value?.trim().orEmpty()
        if (expression.isBlank()) return source
        return source.replaceRange(match.range, "var ${match.groupValues[1]}=function($parameters){return $expression;};")
    }

    private const val MAX_SOURCE_LENGTH = 8 * 1024 * 1024
}
