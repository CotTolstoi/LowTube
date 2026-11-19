package com.lowtube.android.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Pure URL transformations used after the isolated player runtime computes a signature. */
internal object YoutubePlayerUrlTransformer {
    private val signatureParameter = Regex("^[A-Za-z0-9_]{1,32}$")
    private val pathNParameter = Regex("^[A-Za-z0-9_-]{1,2048}$")

    fun applyN(url: String, transformedN: String): String? {
        if (url.length > MAX_URL_LENGTH || transformedN.isBlank() || transformedN.length > MAX_VALUE_LENGTH) return null
        val queryAt = url.indexOf('?')
        if (queryAt < 0) return null
        val fragmentAt = url.indexOf('#', queryAt).takeIf { it >= 0 } ?: url.length
        val query = url.substring(queryAt + 1, fragmentAt)
        var found = false
        val replaced = query.split('&').mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val key = decode(part.substringBefore('=')) ?: return@mapNotNull part
            if (key != "n") return@mapNotNull part
            if (found) return@mapNotNull null
            found = true
            "n=${encode(transformedN)}"
        }
        if (!found) return null
        return url.substring(0, queryAt + 1) + replaced.joinToString("&") + url.substring(fragmentAt)
    }

    fun acceptUrlNTransform(originalUrl: String, transformedUrl: String): String? {
        if (originalUrl.length > MAX_URL_LENGTH || transformedUrl.isBlank() || transformedUrl.length > MAX_URL_LENGTH) return null
        val original = runCatching { URI(originalUrl) }.getOrNull() ?: return null
        val transformed = runCatching { URI(transformedUrl) }.getOrNull() ?: return null
        if (!original.scheme.equals("https", ignoreCase = true) ||
            !original.scheme.equals(transformed.scheme, ignoreCase = true) ||
            !original.host.equals(transformed.host, ignoreCase = true) ||
            original.port != transformed.port ||
            original.rawUserInfo != null || transformed.rawUserInfo != null
        ) return null
        val originalN = queryParameter(original.rawQuery, "n") ?: return null
        val transformedN = queryParameter(transformed.rawQuery, "n") ?: return null
        if (transformedN.isBlank()) return null
        if (original.rawPath == transformed.rawPath) {
            return transformedUrl.takeIf { transformedN != originalN }
        }
        if (original.rawQuery != transformed.rawQuery || original.rawFragment != transformed.rawFragment) return null
        val originalSegments = original.rawPath.orEmpty().split('/')
        val transformedSegments = transformed.rawPath.orEmpty().split('/')
        if (originalSegments.size != transformedSegments.size) return null
        val changedSegments = originalSegments.indices.filter { originalSegments[it] != transformedSegments[it] }
        if (changedSegments.size != 1) return null
        val changedIndex = changedSegments.single()
        if (changedIndex <= 0 || originalSegments[changedIndex - 1] != "n") return null
        val oldSegment = originalSegments[changedIndex]
        val newSegment = transformedSegments[changedIndex]
        if (!pathNParameter.matches(oldSegment) || !pathNParameter.matches(newSegment) || oldSegment == newSegment) return null
        return transformedUrl
    }

    fun applySignatureCipher(cipher: String, signature: String): String? {
        if (cipher.isBlank() || cipher.length > MAX_CIPHER_LENGTH || signature.isBlank() || signature.length > MAX_VALUE_LENGTH) return null
        val fields = LinkedHashMap<String, String>()
        for (part in cipher.split('&')) {
            if (part.isEmpty()) continue
            val key = decode(part.substringBefore('=')) ?: continue
            val value = decode(part.substringAfter('=', "")) ?: continue
            fields.putIfAbsent(key, value)
        }
        val base = fields["url"] ?: return null
        val parameter = fields["sp"]?.takeIf(signatureParameter::matches) ?: "signature"
        if (queryHasParameter(base, parameter)) return base
        val separator = if ('?' in base) if (base.endsWith('?') || base.endsWith('&')) "" else "&" else "?"
        return (base + separator + encode(parameter) + "=" + encode(signature)).takeIf { it.length <= MAX_URL_LENGTH }
    }

    private fun queryHasParameter(url: String, parameter: String): Boolean {
        val queryAt = url.indexOf('?')
        if (queryAt < 0) return false
        val fragmentAt = url.indexOf('#', queryAt).takeIf { it >= 0 } ?: url.length
        return url.substring(queryAt + 1, fragmentAt).split('&').any {
            decode(it.substringBefore('=')) == parameter
        }
    }

    private fun queryParameter(query: String?, name: String): String? = query?.split('&')?.firstNotNullOfOrNull { part ->
        val key = decode(part.substringBefore('=')) ?: return@firstNotNullOfOrNull null
        if (key != name) return@firstNotNullOfOrNull null
        decode(part.substringAfter('=', ""))
    }

    private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private const val MAX_URL_LENGTH = 128 * 1024
    private const val MAX_CIPHER_LENGTH = 64 * 1024
    private const val MAX_VALUE_LENGTH = 16 * 1024
}
