package com.lfcreative.geartracker.core

import java.net.URI
import java.net.URISyntaxException

/** Validation for the user-editable Supabase server URL (Settings). Pure, unit tested. */
object ServerUrl {
    fun normalize(input: String, allowCleartext: Boolean): Result<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return fail("Enter the server URL")
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return fail("That is not a valid URL")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return fail("The URL must start with https://")
        if (scheme == "http" && !allowCleartext) return fail("Use https:// (http is only allowed in debug builds)")
        val host = uri.host
        if (host.isNullOrBlank()) return fail("The URL has no host name")
        if (uri.userInfo != null) return fail("Do not put credentials in the URL")
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) return fail("Remove the query or fragment")
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") return fail("Use the base URL only, without a path")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return Result.success("$scheme://${host.lowercase()}$port")
    }

    private fun fail(message: String): Result<String> = Result.failure(IllegalArgumentException(message))
}
