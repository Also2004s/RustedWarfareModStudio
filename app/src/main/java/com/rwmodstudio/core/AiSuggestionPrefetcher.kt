package com.rwmodstudio.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * AI 建议预取器：在用户停止输入的间隙，**提前**把建议算好并按上下文指纹缓存。
 *
 * 目标：点击「AI 建议」时缓存命中 → 0 延迟出内容，而不是从这一刻才开始等网络。
 *
 * 节流规则（防止烧 token）：
 * - 相邻两次实际请求间隔不小于 [SettingsManager.aiPrefetchMinIntervalMs]（默认 30s）；
 * - 指纹未变化（上下文没动）不重复请求；
 * - 同一时刻只允许一个在途请求；新的上下文会取代未开始的等待任务。
 *
 * 预取走非流式（结果只进缓存，不需要增量渲染），失败不入缓存、仅打日志。
 */
object AiSuggestionPrefetcher {

    private const val TAG = "AiSuggestionPrefetcher"
    private const val MAX_CACHE_ENTRIES = 8

    /** 一次建议请求的完整上下文（与 AiService.suggestCompletion 入参一一对应） */
    data class Request(
        val filePath: String,
        val sectionName: String?,
        val isHiddenAction: Boolean,
        val recentInputLines: String,
        val contextLines: String,
        val currentLine: String,
        val codeTable: String,
        val fileStructure: String,
        val isChineseView: Boolean
    ) {
        /**
         * 上下文指纹：只取真正影响输出的部分（文件/节/光标上下文/当前行/最近编辑），
         * codeTable 与 fileStructure 由节名派生，不入指纹，避免无谓失效。
         */
        fun fingerprint(): String = md5(
            buildString {
                append(filePath).append('|')
                append(sectionName.orEmpty()).append('|')
                append(isHiddenAction).append('|')
                append(isChineseView).append('|')
                append(currentLine.trim()).append('|')
                append(contextLines.trim()).append('|')
                append(recentInputLines.trim())
            }
        )

        private fun md5(s: String): String = try {
            MessageDigest.getInstance("MD5").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            s.hashCode().toString(16)
        }
    }

    /** 指纹 → 建议列表（LRU，access-order） */
    private val cache = object : LinkedHashMap<String, List<AiService.AiSuggestion>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<AiService.AiSuggestion>>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile private var inFlight: Job? = null
    @Volatile private var pendingJob: Job? = null
    @Volatile private var lastRequestedFingerprint: String = ""
    @Volatile private var lastRequestAt: Long = 0L

    /**
     * 命中缓存时立即返回建议（可能为空列表，表示 AI 明确无建议）。
     * 未命中返回 null，调用方需自行发起请求。
     */
    fun peek(req: Request): List<AiService.AiSuggestion>? = synchronized(lock) { cache[req.fingerprint()] }

    /**
     * 调度一次预取。节流/去重/去并发都在这里处理，可安全地在每次文本变化时调用。
     */
    fun schedule(req: Request) {
        if (!SettingsManager.aiPrefetchEnabled) return
        if (!SettingsManager.devAiCompletion) return
        if (!isConfigured()) return

        val fp = req.fingerprint()
        synchronized(lock) {
            if (fp == lastRequestedFingerprint) return // 上下文没变，不重复请求
            if (cache.containsKey(fp)) return // 已算过
            if (inFlight?.isActive == true) return // 有请求在跑，不打断（锁内检查，防 TOCTOU 并发两个请求）
        }

        val wait = SettingsManager.aiPrefetchMinIntervalMs - (System.currentTimeMillis() - lastRequestAt)
        pendingJob?.cancel()
        if (wait > 0) {
            // 距上次请求太近：延后执行；期间若上下文再变，这个等待任务会被新的取代
            pendingJob = scope.launch {
                delay(wait)
                if (isActive) startRequest(req, fp)
            }
        } else {
            pendingJob = null
            startRequest(req, fp)
        }
    }

    /** 实际发起预取请求（非流式），成功后写入缓存 */
    private fun startRequest(req: Request, fp: String) = synchronized(lock) {
        if (inFlight?.isActive == true) return@synchronized
        inFlight = scope.launch {
            lastRequestedFingerprint = fp
            lastRequestAt = System.currentTimeMillis()
            val result = AiService.suggestCompletion(
                filePath = req.filePath,
                sectionName = req.sectionName,
                isHiddenAction = req.isHiddenAction,
                recentInputLines = req.recentInputLines,
                contextLines = req.contextLines,
                currentLine = req.currentLine,
                codeTable = req.codeTable,
                fileStructure = req.fileStructure,
                isChineseView = req.isChineseView,
                streaming = false,
                onPartial = null,
                onPhase = { }
            )
            result.onSuccess { list ->
                synchronized(lock) { cache[fp] = list }
                Log.d(TAG, "prefetch cached: fp=${fp.take(8)}, count=${list.size}")
            }.onFailure { e ->
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "prefetch failed: ${e::class.java.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** 清空缓存与在途任务（切换文件、应用建议导致内容变化、切换中英视图时调用） */
    fun invalidate() {
        pendingJob?.cancel()
        inFlight?.cancel()
        pendingJob = null
        inFlight = null
        synchronized(lock) {
            cache.clear()
            lastRequestedFingerprint = ""
        }
    }

    /** AI 配置是否完整（不完整时预取无意义，直接跳过） */
    private fun isConfigured(): Boolean =
        SettingsManager.aiBaseUrl.isNotBlank() &&
            SettingsManager.aiModel.isNotBlank() &&
            SettingsManager.aiApiKey.isNotBlank()
}
