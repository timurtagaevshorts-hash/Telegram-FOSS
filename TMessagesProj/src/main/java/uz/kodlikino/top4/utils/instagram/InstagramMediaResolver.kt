package uz.kodlikino.top4.utils.instagram

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Instagram Reels havolasidan video manzilini aniqlaydi.
 *
 * 2026 yil Instagram talablari: avval guest sessiya (csrftoken cookie),
 * keyin PolarisPostRootQuery GraphQL (doc_id 27128499623469141).
 */
object InstagramMediaResolver {

    const val ERROR_INVALID_LINK = "instagram_invalid_link"
    const val ERROR_NOT_FOUND = "instagram_not_found"
    const val ERROR_LOGIN_REQUIRED = "instagram_login_required"

    data class Media(
        val shortcode: String,
        val videoUrl: String,
        val thumbnailUrl: String = "",
        val authorName: String = ""
    ) {
        fun fileName(): String = "yuzgram_ig_$shortcode.mp4"
    }

    private data class Session(
        val csrfToken: String = "",
        val cookieHeader: String = "",
        val lsd: String = DEFAULT_LSD,
        val html: String = ""
    )

    private data class GraphQlQuery(
        val docId: String,
        val variables: JSONObject
    )

    private const val APP_ID_WEB = "936619743392459"
    private const val APP_ID_ALT = "1217981644879628"
    private const val ASBD_ID = "129477"
    private const val DEFAULT_LSD = "AVqbxe3J_YA"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private const val CRAWLER_UA =
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private const val SHORTCODE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val linkRegex = Regex(
        "(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am)/" +
            "(?:[A-Za-z0-9_.]+/)?(?:reels?|p|tv)/([A-Za-z0-9_-]+)",
        RegexOption.IGNORE_CASE
    )

    private val cdnVideoRegex = Regex(
        """https?://[^"'\\>\s]*(?:cdninstagram|fbcdn)\.net[^"'\\>\s]*""",
        RegexOption.IGNORE_CASE
    )

    private val cookieJarStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val bucket = cookieJarStore.getOrPut(url.host) { mutableListOf() }
                    bucket.removeAll { old -> cookies.any { it.name == old.name } }
                    bucket.addAll(cookies)
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieJarStore.values.flatten()
                        .filter { it.matches(url) }
                }
            })
            .build()
    }

    fun looksLikeInstagramLink(link: String): Boolean {
        val cleaned = normalizeInput(link)
        if (cleaned.isBlank()) return false
        return linkRegex.containsMatchIn(cleaned) ||
            cleaned.contains("instagram.com", ignoreCase = true) ||
            cleaned.contains("instagr.am", ignoreCase = true)
    }

    fun extractShortcode(link: String): String? =
        linkRegex.find(link.trim())?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun extractBestUrl(text: String): String {
        val trimmed = text.trim()
        val match = linkRegex.find(trimmed)
        if (match != null) {
            return canonicalReelUrl(match.groupValues[1])
        }
        val token = trimmed.split(Regex("\\s+"))
            .firstOrNull { it.contains("instagram.com", true) || it.contains("instagr.am", true) }
            ?: trimmed
        return normalizeInput(token)
    }

    suspend fun resolve(context: Context, link: String): Result<Media> = withContext(Dispatchers.IO) {
        cookieJarStore.clear()
        val bestUrl = extractBestUrl(link)
        val shortcode = extractShortcode(link)
            ?: extractShortcode(bestUrl)
            ?: followShareLink(link.trim())
            ?: followShareLink(bestUrl)
            ?: return@withContext Result.failure(IllegalArgumentException(ERROR_INVALID_LINK))

        val savedCookies = InstagramCookieStore.load(context).orEmpty()
        val session = buildSession(shortcode, savedCookies)
        val canonicalUrl = canonicalReelUrl(shortcode)

        val graphQlQueries = listOf(
            GraphQlQuery(
                docId = "27128499623469141",
                variables = JSONObject()
                    .put("shortcode", shortcode)
                    .put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false)
            ),
            GraphQlQuery(
                docId = "24368985919464652",
                variables = JSONObject().put("shortcode", shortcode)
            ),
            GraphQlQuery(
                docId = "10015901848480474",
                variables = JSONObject().put("shortcode", shortcode)
            ),
            GraphQlQuery(
                docId = "8845758582119845",
                variables = JSONObject().put("shortcode", shortcode)
            )
        )

        for (query in graphQlQueries) {
            for (appId in listOf(APP_ID_WEB, APP_ID_ALT)) {
                fromGraphQl(session, query, appId, canonicalUrl)?.let {
                    return@withContext Result.success(it)
                }
            }
        }

        fromApiGraphQl(session, shortcode, canonicalUrl)?.let {
            return@withContext Result.success(it)
        }

        fromEmbeddedJson(session.html, shortcode)?.let {
            return@withContext Result.success(it)
        }

        fromOembed(canonicalUrl, shortcode, session)?.let {
            return@withContext Result.success(it)
        }

        fromMediaInfo(shortcode, session)?.let {
            return@withContext Result.success(it)
        }

        fromCrawlerPage(shortcode)?.let {
            return@withContext Result.success(it)
        }

        fromEmbedPage(shortcode, session)?.let {
            return@withContext Result.success(it)
        }

        if (savedCookies.isBlank()) {
            Result.failure(IllegalStateException(ERROR_LOGIN_REQUIRED))
        } else {
            Result.failure(IllegalStateException(ERROR_NOT_FOUND))
        }
    }

    suspend fun downloadToCache(
        context: Context,
        media: Media,
        onProgress: ((Int) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "instagram_videos").apply { mkdirs() }
        val out = File(dir, media.fileName())
        if (out.exists() && out.length() > 0L) {
            onProgress?.invoke(100)
            return@withContext out
        }
        val cookieHeader = InstagramCookieStore.load(context).orEmpty()
        val builder = Request.Builder()
            .url(media.videoUrl)
            .header("User-Agent", MOBILE_UA)
            .header("Referer", canonicalReelUrl(media.shortcode))
            .header("Origin", "https://www.instagram.com")
        if (cookieHeader.isNotBlank()) {
            builder.header("Cookie", cookieHeader)
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("empty body")
            val total = body.contentLength().takeIf { it > 0L } ?: -1L
            var read = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0L) {
                            onProgress?.invoke(((read * 100L) / total).toInt().coerceIn(0, 99))
                        }
                    }
                }
            }
        }
        if (!out.exists() || out.length() <= 0L) {
            out.delete()
            throw IllegalStateException("download failed")
        }
        onProgress?.invoke(100)
        out
    }

    private fun buildSession(shortcode: String, savedCookieHeader: String): Session {
        primeHomepage(savedCookieHeader)
        val reelHtml = fetchText(
            url = canonicalReelUrl(shortcode),
            userAgent = DESKTOP_UA,
            referer = "https://www.instagram.com/",
            cookieHeader = mergedCookieHeader(savedCookieHeader)
        ).orEmpty()
        val csrf = cookieValue("csrftoken")
            ?: InstagramCookieStore.csrfFromHeader(savedCookieHeader)
            ?: extractCsrfFromHtml(reelHtml)
            ?: ""
        return Session(
            csrfToken = csrf,
            cookieHeader = mergedCookieHeader(savedCookieHeader),
            lsd = extractLsd(reelHtml) ?: DEFAULT_LSD,
            html = reelHtml
        )
    }

    private fun primeHomepage(savedCookieHeader: String) {
        fetchText(
            url = "https://www.instagram.com/",
            userAgent = DESKTOP_UA,
            cookieHeader = savedCookieHeader
        )
    }

    private fun fromGraphQl(
        session: Session,
        query: GraphQlQuery,
        appId: String,
        referer: String
    ): Media? {
        val variablesJson = query.variables.toString()
        val encodedVars = URLEncoder.encode(variablesJson, Charsets.UTF_8.name())
        val formBody = "variables=$encodedVars&doc_id=${query.docId}&server_timestamps=true"
        val body = postGraphQl(session, formBody, appId, referer) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val shortcode = query.variables.optString("shortcode")
        return parseMediaFromJson(json, shortcode)
    }

    private fun fromApiGraphQl(session: Session, shortcode: String, referer: String): Media? {
        val variablesJson = JSONObject().put("shortcode", shortcode).toString()
        val encodedVars = URLEncoder.encode(variablesJson, Charsets.UTF_8.name())
        val formBody = "variables=$encodedVars&doc_id=10015901848480474&lsd=${session.lsd}"
        val body = post(
            url = "https://www.instagram.com/api/graphql",
            formBody = formBody,
            session = session,
            appId = APP_ID_WEB,
            referer = referer
        ) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return parseMediaFromJson(json, shortcode)
    }

    private fun fromOembed(pageUrl: String, shortcode: String, session: Session): Media? {
        val encoded = URLEncoder.encode(pageUrl, Charsets.UTF_8.name())
        val body = fetchText(
            url = "https://i.instagram.com/api/v1/oembed/?url=$encoded",
            userAgent = MOBILE_UA,
            referer = pageUrl,
            cookieHeader = session.cookieHeader,
            extra = {
                header("x-ig-app-id", APP_ID_WEB)
                header("Accept", "application/json")
            }
        ) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val mediaId = json.optString("media_id").ifBlank { json.optString("mediaId") }
        if (mediaId.isNotBlank()) {
            fromMediaInfoById(mediaId, shortcode, session)?.let { return it }
        }
        return parseMediaFromJson(json, shortcode)
    }

    private fun fromMediaInfo(shortcode: String, session: Session): Media? {
        val mediaId = shortcodeToMediaId(shortcode) ?: return null
        return fromMediaInfoById(mediaId, shortcode, session)
    }

    private fun fromMediaInfoById(mediaId: String, shortcode: String, session: Session): Media? {
        val body = fetchText(
            url = "https://i.instagram.com/api/v1/media/$mediaId/info/",
            userAgent = MOBILE_UA,
            referer = canonicalReelUrl(shortcode),
            cookieHeader = session.cookieHeader,
            extra = {
                header("x-ig-app-id", APP_ID_WEB)
                header("x-csrftoken", session.csrfToken)
                header("Accept", "application/json")
            }
        ) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return parseMediaFromJson(json, shortcode)
    }

    private fun fromCrawlerPage(shortcode: String): Media? {
        val html = fetchText(
            url = canonicalReelUrl(shortcode),
            userAgent = CRAWLER_UA,
            referer = "https://www.instagram.com/"
        ) ?: return null
        return fromPageHtml(html, shortcode)
    }

    private fun fromEmbedPage(shortcode: String, session: Session): Media? {
        val html = fetchText(
            url = "https://www.instagram.com/reel/$shortcode/embed/captioned/",
            userAgent = MOBILE_UA,
            referer = canonicalReelUrl(shortcode),
            cookieHeader = session.cookieHeader
        ) ?: return null
        return fromPageHtml(html, shortcode)
    }

    private fun fromEmbeddedJson(html: String, shortcode: String): Media? {
        if (html.isBlank()) return null
        extractNextData(html, shortcode)?.let { return it }
        extractJsonLdVideo(html, shortcode)?.let { return it }
        return fromPageHtml(html, shortcode)
    }

    private fun extractNextData(html: String, shortcode: String): Media? {
        val regex = Regex(
            """<script[^>]*id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE
        )
        val jsonText = regex.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (jsonText.isBlank()) return null
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        return parseMediaFromJson(json, shortcode)
    }

    private fun extractJsonLdVideo(html: String, shortcode: String): Media? {
        val ldRegex = Regex(
            """<script[^>]*type="application/ld\+json"[^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE
        )
        for (match in ldRegex.findAll(html)) {
            val jsonText = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (jsonText.isBlank()) continue
            val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
            parseMediaFromJson(json, shortcode)?.let { return it }
        }
        return null
    }

    private fun fromPageHtml(html: String, shortcode: String): Media? {
        if (html.isBlank()) return null
        findCdnVideoUrl(html)?.let { url ->
            val thumb = Regex("""property="og:image"[^>]*content="([^"]+)"""")
                .find(html)?.groupValues?.getOrNull(1)?.let(::unescape)
            val author = Regex("""property="og:title"[^>]*content="([^"]+)"""")
                .find(html)?.groupValues?.getOrNull(1)?.let(::unescape)
            return Media(shortcode, url, thumb.orEmpty(), author.orEmpty())
        }
        val patterns = listOf(
            Regex(""""video_url"\s*:\s*"([^"]+)""""),
            Regex(""""playback_url"\s*:\s*"([^"]+)""""),
            Regex(""""video_versions"\s*:\s*\[\s*\{[^}]*?"url"\s*:\s*"([^"]+)"""")
        )
        for (pattern in patterns) {
            val raw = pattern.find(html)?.groupValues?.getOrNull(1)
            if (!raw.isNullOrBlank()) {
                return Media(
                    shortcode = shortcode,
                    videoUrl = unescape(raw),
                    thumbnailUrl = Regex(""""display_url"\s*:\s*"([^"]+)"""")
                        .find(html)?.groupValues?.getOrNull(1)?.let(::unescape).orEmpty()
                )
            }
        }
        return null
    }

    private fun parseMediaFromJson(json: JSONObject, shortcode: String): Media? {
        val webInfo = json.optJSONObject("data")
            ?.optJSONObject("xdt_api__v1__media__shortcode__web_info")
        webInfo?.optJSONArray("items")?.optJSONObject(0)?.let { item ->
            parseMediaItem(item, shortcode)?.let { return it }
        }
        json.optJSONObject("data")?.optJSONObject("xdt_shortcode_media")?.let { item ->
            parseMediaItem(item, shortcode)?.let { return it }
        }
        val videoUrl = findVideoUrlInJson(json) ?: return null
        val thumb = findStringInJson(json, listOf("display_url", "thumbnail_src", "thumbnail_url"))
        val author = findStringInJson(json, listOf("username", "author_name"))
            ?: json.optJSONObject("user")?.optString("username")
            ?: json.optJSONObject("owner")?.optString("username")
        return Media(
            shortcode = shortcode,
            videoUrl = videoUrl,
            thumbnailUrl = thumb.orEmpty(),
            authorName = author.orEmpty()
        )
    }

    private fun parseMediaItem(item: JSONObject, shortcode: String): Media? {
        val videoUrl = findVideoUrlInJson(item) ?: return null
        val thumb = item.optJSONObject("image_versions2")
            ?.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optString("url")
            .orEmpty()
            .ifBlank {
                findStringInJson(item, listOf("display_url", "thumbnail_src", "thumbnail_url")).orEmpty()
            }
        val author = item.optJSONObject("user")?.optString("username")
            ?: item.optJSONObject("owner")?.optString("username")
            ?: findStringInJson(item, listOf("username")).orEmpty()
        return Media(
            shortcode = item.optString("code").ifBlank { shortcode },
            videoUrl = videoUrl,
            thumbnailUrl = thumb,
            authorName = author
        )
    }

    private fun findVideoUrlInJson(root: Any?): String? {
        when (root) {
            is JSONObject -> {
                root.optString("video_url").takeIf { isVideoUrl(it) }?.let { return it }
                root.optString("contentUrl").takeIf { isVideoUrl(it) }?.let { return it }
                root.optJSONArray("video_versions")?.let { versions ->
                    var best = ""
                    var bestWidth = -1
                    for (i in 0 until versions.length()) {
                        val obj = versions.optJSONObject(i) ?: continue
                        val url = obj.optString("url")
                        if (!isVideoUrl(url)) continue
                        val width = obj.optInt("width", 0)
                        if (width >= bestWidth) {
                            bestWidth = width
                            best = url
                        }
                    }
                    if (best.isNotBlank()) return best
                }
                val keys = root.keys()
                while (keys.hasNext()) {
                    findVideoUrlInJson(root.get(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until root.length()) {
                    findVideoUrlInJson(root.get(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun findStringInJson(root: Any?, keys: List<String>): String? {
        when (root) {
            is JSONObject -> {
                for (key in keys) {
                    val value = root.optString(key)
                    if (value.isNotBlank() && key != "video_url") return value
                }
                val objKeys = root.keys()
                while (objKeys.hasNext()) {
                    findStringInJson(root.get(objKeys.next()), keys)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until root.length()) {
                    findStringInJson(root.get(i), keys)?.let { return it }
                }
            }
        }
        return null
    }

    private fun isVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return url.startsWith("http") &&
            (url.contains("cdninstagram") || url.contains("fbcdn") || url.contains(".mp4"))
    }

    private fun findCdnVideoUrl(source: String): String? {
        val match = cdnVideoRegex.find(source)?.value ?: return null
        return unescape(match).substringBefore(' ')
    }

    private fun shortcodeToMediaId(shortcode: String): String? {
        var acc = BigInteger.ZERO
        val base = BigInteger.valueOf(64L)
        for (ch in shortcode) {
            val index = SHORTCODE_ALPHABET.indexOf(ch)
            if (index < 0) return null
            acc = acc.multiply(base).add(BigInteger.valueOf(index.toLong()))
        }
        return acc.takeIf { it.signum() > 0 }?.toString()
    }

    private fun followShareLink(link: String): String? {
        val cleaned = link.trim()
        if (!cleaned.contains("instagram.com", ignoreCase = true) &&
            !cleaned.contains("instagr.am", ignoreCase = true)
        ) {
            return null
        }
        return runCatching {
            val url = if (cleaned.startsWith("http")) cleaned else "https://$cleaned"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_UA)
                .build()
            client.newCall(request).execute().use { response ->
                extractShortcode(response.request.url.toString())
                    ?: response.body?.string()?.let { html ->
                        linkRegex.find(html)?.groupValues?.get(1)
                    }
            }
        }.getOrNull()
    }

    private fun postGraphQl(
        session: Session,
        formBody: String,
        appId: String,
        referer: String
    ): String? = post(
        url = "https://www.instagram.com/graphql/query",
        formBody = formBody,
        session = session,
        appId = appId,
        referer = referer
    )

    private fun post(
        url: String,
        formBody: String,
        session: Session,
        appId: String,
        referer: String
    ): String? {
        val body = formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", DESKTOP_UA)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://www.instagram.com")
            .header("Referer", referer)
            .header("X-IG-App-ID", appId)
            .header("X-FB-LSD", session.lsd)
            .header("X-ASBD-ID", ASBD_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
        if (session.csrfToken.isNotBlank()) {
            builder.header("X-CSRFToken", session.csrfToken)
        }
        if (session.cookieHeader.isNotBlank()) {
            builder.header("Cookie", session.cookieHeader)
        }
        return executeForBody(builder.build())
    }

    private fun fetchText(
        url: String,
        userAgent: String = MOBILE_UA,
        referer: String? = null,
        cookieHeader: String = "",
        extra: Request.Builder.() -> Unit = {}
    ): String? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
        referer?.let { builder.header("Referer", it) }
        if (cookieHeader.isNotBlank()) {
            builder.header("Cookie", cookieHeader)
        }
        builder.apply(extra)
        return executeForBody(builder.build())
    }

    private fun executeForBody(request: Request): String? {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    private fun mergedCookieHeader(saved: String): String {
        val fromJar = cookieJarStore.values.flatten()
            .joinToString("; ") { "${it.name}=${it.value}" }
        return when {
            saved.isBlank() -> fromJar
            fromJar.isBlank() -> saved
            else -> "$saved; $fromJar"
        }
    }

    private fun cookieValue(name: String): String? =
        cookieJarStore.values.flatten()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }

    private fun extractCsrfFromHtml(html: String): String? {
        val patterns = listOf(
            Regex(""""csrftoken"\s*:\s*"([^"]+)""""),
            Regex("""content="([^"]+)"[^>]*name="csrf-token"""")
        )
        for (pattern in patterns) {
            val token = pattern.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!token.isNullOrBlank()) return token
        }
        return null
    }

    private fun extractLsd(html: String): String? {
        val patterns = listOf(
            Regex(""""LSD",\[\],\{"token":"([^"]+)""""),
            Regex(""""lsd":"([^"]+)""""),
            Regex(""""LSD":\{"token":"([^"]+)"""")
        )
        for (pattern in patterns) {
            val token = pattern.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!token.isNullOrBlank()) return token
        }
        return null
    }

    private fun normalizeInput(link: String): String =
        link.trim()
            .removePrefix("@")
            .substringBefore('?')
            .substringBefore('#')
            .trim()

    private fun canonicalReelUrl(shortcode: String): String =
        "https://www.instagram.com/reel/$shortcode/"

    private fun unescape(raw: String): String {
        val unicoded = Regex("\\\\u([0-9a-fA-F]{4})").replace(raw) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return unicoded
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
    }
}
