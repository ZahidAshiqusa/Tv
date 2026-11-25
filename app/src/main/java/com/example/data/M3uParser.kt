package com.example.data

import android.net.Uri
import android.util.Base64
import com.example.data.model.ChannelCategory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ParsedM3uChannel(
    val name: String,
    val streamUrl: String,
    val category: ChannelCategory,
    val logoUrl: String,
    val referrer: String = "",
    val userAgent: String = ""
)

data class TokenExpiryInfo(
    val isSignedToken: Boolean,
    val minutesRemaining: Int,
    val isExpired: Boolean
)

object M3uParser {

    private val logoAttrRegex = Regex("""tvg-logo="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val groupAttrRegex = Regex("""group-title="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val refAttrRegex = Regex("""http-referrer="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val uaAttrRegex = Regex("""http-user-agent="([^"]+)"""", RegexOption.IGNORE_CASE)

    fun isValidStreamUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    fun normalizeChannelKey(name: String): String =
        name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")

    fun parsePlaylist(rawText: String): List<ParsedM3uChannel> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val results = mutableListOf<ParsedM3uChannel>()

        var pendingName: String? = null
        var pendingLogo = ""
        var pendingGroup = ""
        var pendingReferrer = ""
        var pendingUserAgent = ""

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> continue
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingLogo = logoAttrRegex.find(line)?.groupValues?.getOrNull(1).orEmpty().trim()
                    pendingGroup = groupAttrRegex.find(line)?.groupValues?.getOrNull(1).orEmpty().trim()
                    pendingReferrer = refAttrRegex.find(line)?.groupValues?.getOrNull(1).orEmpty().trim()
                    pendingUserAgent = uaAttrRegex.find(line)?.groupValues?.getOrNull(1).orEmpty().trim()
                    val commaIdx = line.lastIndexOf(',')
                    pendingName = if (commaIdx >= 0 && commaIdx + 1 < line.length) {
                        line.substring(commaIdx + 1).trim()
                    } else {
                        null
                    }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(":", "").trim()
                    when {
                        opt.startsWith("http-referrer=", ignoreCase = true) -> {
                            pendingReferrer = opt.substringAfter("=", "").trim()
                        }
                        opt.startsWith("http-user-agent=", ignoreCase = true) -> {
                            pendingUserAgent = opt.substringAfter("=", "").trim()
                        }
                    }
                }
                line.startsWith("#") -> continue
                isValidStreamUrl(line) -> {
                    val resolvedName = pendingName?.takeIf { it.isNotBlank() }
                        ?: deriveNameFromUrl(line, results.size + 1)
                    val category = detectCategory(resolvedName, pendingGroup)
                    results.add(
                        ParsedM3uChannel(
                            name = resolvedName,
                            streamUrl = line,
                            category = category,
                            logoUrl = pendingLogo,
                            referrer = pendingReferrer,
                            userAgent = pendingUserAgent
                        )
                    )
                    pendingName = null
                    pendingLogo = ""
                    pendingGroup = ""
                    pendingReferrer = ""
                    pendingUserAgent = ""
                }
                else -> {
                    // Support simple "Channel Name https://..." lines too
                    val httpIdx = line.indexOf("http", ignoreCase = true)
                    if (httpIdx > 0) {
                        val candidateName = line.substring(0, httpIdx).trim(' ', '-', '_', ':')
                        val candidateUrl = line.substring(httpIdx).trim()
                        if (isValidStreamUrl(candidateUrl) && candidateName.isNotBlank()) {
                            results.add(
                                ParsedM3uChannel(
                                    name = candidateName,
                                    streamUrl = candidateUrl,
                                    category = detectCategory(candidateName, ""),
                                    logoUrl = ""
                                )
                            )
                        }
                    }
                }
            }
        }
        return results
    }

    fun detectCategory(name: String, groupHint: String = ""): ChannelCategory {
        val combined = "$groupHint $name".lowercase(Locale.ROOT)
        return when {
            combined.contains("sport") || combined.contains("cricket") || combined.contains("cup") || combined.contains("ten ") -> ChannelCategory.SPORTS
            combined.contains("islam") || combined.contains("paigham") || combined.contains("madani") || combined.contains("qtv") || combined.contains("peace") -> ChannelCategory.ISLAMIC
            combined.contains("movie") || combined.contains("film") || combined.contains("cinema") || combined.contains("documentary") || combined.contains("discovery") -> ChannelCategory.MOVIES
            combined.contains("news") || combined.contains("dawn") || combined.contains("samaa") || combined.contains("jazeera") || combined.contains("cnn") || combined.contains("today") || combined.contains("aaj") -> ChannelCategory.NEWS
            else -> ChannelCategory.ENTERTAINMENT
        }
    }

    /**
     * Decodes Nimble Streamer wmsAuthSign Base64 query parameter to report live token validity.
     * Example payload: server_time=11/23/2026 11:28:26 AM&hash_value=...&validminutes=60
     */
    fun parseWmsAuthSignExpiry(streamUrl: String, nowMillis: Long = System.currentTimeMillis()): TokenExpiryInfo {
        return runCatching {
            val uri = Uri.parse(streamUrl)
            val sign = uri.getQueryParameter("wmsAuthSign")
                ?: return TokenExpiryInfo(isSignedToken = false, minutesRemaining = -1, isExpired = false)
            val decoded = String(Base64.decode(sign, Base64.DEFAULT), Charsets.UTF_8)
            val params = decoded.split("&").associate { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) pair.substring(0, idx) to pair.substring(idx + 1) else pair to ""
            }
            val serverTimeStr = params["server_time"] ?: return TokenExpiryInfo(false, -1, false)
            val validMinutes = params["validminutes"]?.toIntOrNull() ?: 60

            val formatter = SimpleDateFormat("MM/dd/yyyy hh:mm:ss a", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val issuedAt = formatter.parse(serverTimeStr)?.time ?: return TokenExpiryInfo(false, -1, false)
            val expiresAt = issuedAt + validMinutes * 60_000L
            val diffMinutes = ((expiresAt - nowMillis) / 60_000L).toInt()
            TokenExpiryInfo(
                isSignedToken = true,
                minutesRemaining = diffMinutes.coerceAtLeast(0),
                isExpired = expiresAt <= nowMillis
            )
        }.getOrElse {
            TokenExpiryInfo(isSignedToken = false, minutesRemaining = -1, isExpired = false)
        }
    }

    private fun deriveNameFromUrl(url: String, index: Int): String {
        val segments = runCatching { Uri.parse(url).pathSegments }.getOrNull().orEmpty()
        val meaningful = segments.firstOrNull { seg ->
            seg.length > 3 && !seg.equals("jazzauth", true) && !seg.equals("live", true) && !seg.endsWith(".m3u8", true)
        }
        return meaningful
            ?.replace("-abr", "", ignoreCase = true)
            ?.replace('_', ' ')
            ?.replace('-', ' ')
            ?.trim()
            ?.replaceFirstChar { it.uppercaseChar() }
            ?: "Live Channel $index"
    }
}
