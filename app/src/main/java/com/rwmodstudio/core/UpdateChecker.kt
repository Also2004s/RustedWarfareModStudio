package com.rwmodstudio.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新：通过 GitHub Releases API 拉取仓库最新发布版本，
 * 与当前版本号比较判断是否需要更新（当前版本由调用方传入）。
 */
object UpdateChecker {

    /** 仓库最新 Release 接口（GitHub 无鉴权请求有 60 次/小时限制，点击触发足够） */
    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/Also2004s/RustedWarfareModStudio/releases/latest"

    /** 检查失败时的兜底 Release 列表页（无网络时也用于提示） */
    private const val RELEASES_PAGE_URL =
        "https://github.com/Also2004s/RustedWarfareModStudio/releases"

    /**
     * GitHub 代理加速前缀：国内网络直连 github.com 常被墙，用 gh-proxy 中转。
     * 用法：代理URL = GH_PROXY_PREFIX + 原始直链（如 github.com / api.github.com）。
     */
    const val GH_PROXY_PREFIX = "https://gh-proxy.com/"

    /** 给原始直链加上代理前缀 */
    fun proxiedUrl(url: String): String = GH_PROXY_PREFIX + url

    private const val TIMEOUT_MS = 10_000
    private val json = Json { ignoreUnknownKeys = true }

    /** 最新版本信息 */
    data class UpdateInfo(
        /** 去前缀 v 的版本号，如 1.3.0 */
        val versionName: String,
        /** 原始 tag 名，如 v1.3.0 */
        val tagName: String,
        /** Release 详情页地址 */
        val htmlUrl: String,
        /** 更新说明 */
        val body: String,
        /** 发布时间 */
        val publishedAt: String?,
        /** APK 附件直链（无附件则为 null，跳详情页） */
        val apkUrl: String?
    )

    @Serializable
    private data class ReleaseAsset(
        val name: String? = null,
        val browser_download_url: String? = null
    )

    @Serializable
    private data class Release(
        val tag_name: String? = null,
        val name: String? = null,
        val html_url: String? = null,
        val body: String? = null,
        val published_at: String? = null,
        val assets: List<ReleaseAsset>? = null
    )

    /**
     * 拉取最新版本信息（阻塞，需在 IO 线程调用），失败抛出异常。
     * 直连 GitHub API 失败（如国内网络被墙）时自动走 gh-proxy 镜像重试。
     * 注意：仓库还没有 Release 时 GitHub API 返回 404，此时视为无新版本。
     */
    fun fetchLatest(): UpdateInfo {
        var lastError: Exception? = null
        for (url in listOf(RELEASES_LATEST_URL, proxiedUrl(RELEASES_LATEST_URL))) {
            try {
                return requestLatest(url)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("检查更新失败")
    }

    private fun requestLatest(url: String): UpdateInfo {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "RustedWarfareModStudio")
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub API 返回 ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val rel = json.decodeFromString<Release>(body)
            val tag = rel.tag_name ?: rel.name ?: ""
            return UpdateInfo(
                versionName = tag.removePrefix("v").trim(),
                tagName = tag,
                htmlUrl = rel.html_url ?: RELEASES_PAGE_URL,
                body = rel.body?.trim() ?: "",
                publishedAt = rel.published_at,
                apkUrl = rel.assets
                    ?.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }
                    ?.browser_download_url
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 兜底跳转地址：最新 Release 详情页 */
    fun releasesPageUrl(): String = RELEASES_PAGE_URL

    /**
     * 版本号大小比较：把版本字符串拆成数字段逐个比较，忽略非数字后缀（如 1.2.0-Max 视为 1.2.0）。
     * @return remote 是否比 current 更新
     */
    fun isNewerThan(remote: String, current: String): Boolean {
        val r = versionParts(remote)
        val c = versionParts(current)
        val maxLen = maxOf(r.size, c.size)
        for (i in 0 until maxLen) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun versionParts(version: String): List<Int> {
        return version.split('.').mapNotNull { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() }
    }
}
