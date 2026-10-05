package com.example.data

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A published release that is newer than what this device is running. */
data class AppUpdate(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String
)

/** One file attached to a GitHub release: its name and its direct download URL. */
data class ReleaseAsset(val name: String, val url: String)

private data class LatestRelease(
    val versionCode: Int,
    val versionName: String,
    val assets: List<ReleaseAsset>,
    val pageUrl: String
)

/**
 * Asks GitHub for the newest release, at most once every [CHECK_INTERVAL_MS].
 *
 * Deliberately bare: no token, no device id, no analytics — the request carries
 * nothing but a User-Agent, so it cannot be tied back to a person. Every failure
 * path (offline, no releases yet, malformed body, rate limit) answers "nothing to
 * offer" and stays silent: an update prompt is a courtesy, not something to
 * interrupt the user for.
 */
class UpdateChecker(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        // A debug build has no release matching it and would nag on every launch.
        if (BuildConfig.DEBUG) return@withContext null

        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return@withContext null
        // Recorded before the call: a failing endpoint must not be retried every launch.
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val latest = fetchLatest() ?: return@withContext null
        if (latest.versionCode <= BuildConfig.VERSION_CODE) return@withContext null
        if (latest.versionCode <= prefs.getInt(KEY_DISMISSED, 0)) return@withContext null

        AppUpdate(
            versionName = latest.versionName.removePrefix("v"),
            versionCode = latest.versionCode,
            // Device ABI first, universal second, release page as the last resort.
            apkUrl = chooseApkUrl(latest.assets, Build.SUPPORTED_ABIS.firstOrNull())
                ?: latest.pageUrl
        )
    }

    /** Stops the banner coming back for this version; the next release offers again. */
    fun markDismissed(versionCode: Int) {
        prefs.edit().putInt(KEY_DISMISSED, versionCode).apply()
    }

    private fun fetchLatest(): LatestRelease? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty(HEADER_ACCEPT, HEADER_ACCEPT_VALUE)
                // GitHub refuses requests with no User-Agent, so this is a requirement.
                setRequestProperty(HEADER_USER_AGENT, USER_AGENT)
            }
            // 404 simply means no release has been published yet.
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            parseRelease(readCapped(conn.inputStream, MAX_BODY_BYTES))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseRelease(body: String): LatestRelease? {
        val root = JSONObject(body)
        val versionCode = versionCodeOf(root.optString("tag_name")) ?: return null
        val pageUrl = root.optString("html_url")
        if (pageUrl.isEmpty()) return null

        val assets = mutableListOf<ReleaseAsset>()
        val array = root.optJSONArray("assets")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name")
                val url = item.optString("browser_download_url")
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    assets += ReleaseAsset(name, url)
                }
            }
        }

        return LatestRelease(
            versionCode = versionCode,
            versionName = root.optString("tag_name"),
            assets = assets,
            pageUrl = pageUrl
        )
    }

    /**
     * Reads at most [max] bytes so a runaway response cannot eat the heap.
     * A cut-off body is a parse failure later, which is handled like any other.
     */
    private fun readCapped(stream: InputStream, max: Int): String {
        val out = StringBuilder()
        val buffer = ByteArray(8 * 1024)
        while (out.length < max) {
            val read = stream.read(buffer, 0, minOf(buffer.size, max - out.length))
            if (read < 0) break
            out.append(String(buffer, 0, read, Charsets.UTF_8))
        }
        return out.toString()
    }

    companion object {
        /** GitHub allows 60 unauthenticated calls per hour and IP; 24 a day is nothing. */
        const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L

        private const val API_URL =
            "https://api.github.com/repos/Mahdilvl20/Bus-Transportation-App/releases/latest"
        private const val USER_AGENT = "IsfahanBus-UpdateCheck"
        private const val HEADER_ACCEPT = "Accept"
        private const val HEADER_ACCEPT_VALUE = "application/vnd.github+json"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val MAX_BODY_BYTES = 512 * 1024
        private const val PREFS_NAME = "update_check"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_DISMISSED = "dismissed_version_code"
    }
}

/**
 * `v1.2.3` (or `1.2.3`) → 1_002_003, the same `major*1000000 + minor*1000 + patch`
 * the release workflow writes into `versionCode`. Three digits per component is what
 * keeps two different tags from ever sharing a code — the earlier *100/+100 mapping
 * sent v1.2.300 and v1.5.0 both to 10500, which would have hidden that release.
 * Anything else (a fourth component, a component over 999) is not comparable, so it
 * answers null rather than guessing.
 */
internal fun versionCodeOf(tag: String): Int? {
    val parts = tag.trim().removePrefix("v").split(".")
    if (parts.size != 3) return null
    val numbers = parts.map { it.toIntOrNull() ?: return null }
    if (numbers.any { it !in 0..999 }) return null
    val (major, minor, patch) = numbers
    return major * 1_000_000 + minor * 1_000 + patch
}

/**
 * Picks the APK built for this device: the split named after its primary ABI first,
 * the universal one second. The file names must stay in step with `renameReleaseApks`
 * and with the assets the release workflow uploads.
 */
internal fun chooseApkUrl(assets: List<ReleaseAsset>, primaryAbi: String?): String? {
    val wanted = buildList {
        primaryAbi?.let { add("IsfahanBus-$it-release.apk") }
        add("IsfahanBus-universal-release.apk")
    }
    return wanted.firstNotNullOfOrNull { name ->
        assets.firstOrNull { it.name == name }?.url
    }
}
