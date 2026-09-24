package com.uasflightdeck.sentry.core

import com.uasflightdeck.sentry.core.Parsers.arr
import com.uasflightdeck.sentry.core.Parsers.num
import com.uasflightdeck.sentry.core.Parsers.obj
import com.uasflightdeck.sentry.core.Parsers.str

/**
 * Semantic version, enough for release tags: `v0.3.0`, `0.3`, `1.2.3-beta.1`.
 * A leading `v`/`V` is stripped; missing minor/patch count as 0; a build suffix
 * (`+…`) is ignored; a pre-release (`-…`) sorts BELOW the same release.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: String? = null) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        return when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> comparePre(pre, other.pre)
        }
    }

    override fun toString() = "$major.$minor.$patch" + (pre?.let { "-$it" } ?: "")

    companion object {
        private val RE = Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(raw: String?): SemVer? {
            val m = RE.matchEntire(raw?.trim() ?: return null) ?: return null
            fun g(i: Int) = m.groupValues[i]
            return SemVer(g(1).toIntOrNull() ?: return null, g(2).ifEmpty { "0" }.toInt(), g(3).ifEmpty { "0" }.toInt(), g(4).ifEmpty { null })
        }

        /** SemVer §11: dot-separated identifiers; numeric < alphanumeric; shorter wins ties. */
        private fun comparePre(a: String, b: String): Int {
            val x = a.split('.'); val y = b.split('.')
            for (i in 0 until minOf(x.size, y.size)) {
                val xn = x[i].toIntOrNull(); val yn = y[i].toIntOrNull()
                val c = when {
                    xn != null && yn != null -> compareValues(xn, yn)
                    xn != null -> -1
                    yn != null -> 1
                    else -> x[i].compareTo(y[i])
                }
                if (c != 0) return c
            }
            return compareValues(x.size, y.size)
        }

        /**
         * Is the release [latestTag] newer than the running [current] version?
         * Anything unparseable is "not newer": a garbled tag must never nag the pilot.
         */
        fun isNewer(latestTag: String?, current: String?): Boolean {
            val l = parse(latestTag) ?: return false
            val c = parse(current) ?: return false
            return l > c
        }
    }
}

/** The fields Sentry needs from GitHub's `GET /repos/{owner}/{repo}/releases/latest`. */
data class ReleaseInfo(
    val tag: String,
    val version: SemVer,
    val name: String?,
    val notes: String,
    /** The `flight-deck-sentry.apk` asset, else the stable latest/download link. */
    val apkUrl: String,
    val apkSize: Long?,
    val htmlUrl: String?,
    val publishedAt: String?,
)

object Releases {
    const val REPO = "drobinson911/flight-deck-sentry"
    const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val APK_NAME = "flight-deck-sentry.apk"
    const val STABLE_APK_URL = "https://github.com/$REPO/releases/latest/download/$APK_NAME"

    /** Null when the JSON is not a usable, published, non-draft release with a semver tag. */
    fun parseLatest(json: String): ReleaseInfo? = runCatching {
        val o = Parsers.parse(json).obj() ?: return null
        if (o.str("draft") == "true") return null
        val tag = o.str("tag_name") ?: return null
        val v = SemVer.parse(tag) ?: return null
        val asset = o["assets"].arr()?.mapNotNull { it.obj() }?.firstOrNull { it.str("name") == APK_NAME }
        ReleaseInfo(
            tag = tag, version = v, name = o.str("name"),
            notes = o.str("body")?.trim().orEmpty(),
            apkUrl = asset?.str("browser_download_url") ?: STABLE_APK_URL,
            apkSize = asset?.num("size")?.toLong(),
            htmlUrl = o.str("html_url"), publishedAt = o.str("published_at"),
        )
    }.getOrNull()
}

/**
 * When to hit the GitHub API (60 requests/hour unauthenticated, per IP): at most
 * once per [okIntervalMs] after a successful check, and no more than once per
 * [retryIntervalMs] after a failed one (offline, rate-limited). A manual
 * "Check for update" always goes.
 */
object UpdatePolicy {
    const val OK_INTERVAL_MS = 24 * 3600_000L
    const val RETRY_INTERVAL_MS = 3600_000L

    fun shouldCheck(nowMs: Long, lastSuccessMs: Long, lastAttemptMs: Long, manual: Boolean = false,
                    okIntervalMs: Long = OK_INTERVAL_MS, retryIntervalMs: Long = RETRY_INTERVAL_MS): Boolean {
        if (manual) return true
        // A clock that jumped backwards: treat as due rather than never checking again.
        if (lastSuccessMs > nowMs || lastAttemptMs > nowMs) return true
        return nowMs - lastSuccessMs >= okIntervalMs && nowMs - lastAttemptMs >= retryIntervalMs
    }

    /** Post the "update available" notification once per version. */
    fun shouldNotify(latest: String?, current: String, alreadyNotified: String?): Boolean =
        SemVer.isNewer(latest, current) && SemVer.parse(latest) != SemVer.parse(alreadyNotified)
}
