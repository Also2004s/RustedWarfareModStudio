package com.rwmodstudio.core.translation

import com.rwmodstudio.core.SettingsManager

/**
 * 翻译禁用规则指纹。
 *
 * 文件翻译缓存只以「翻译库内容」为版本时，开关/禁用词/白名单变化不会让旧缓存失效：
 * 清理任务一旦没跑到（2 秒防抖内杀进程、引擎未就绪直接 return 等），
 * 旧规则生成的译文会被永远判定为新鲜。把本指纹拼进缓存版本即可兜底：
 * 规则一变，旧记录自动过期重译。
 */
internal object TranslationRules {

    /** 规则语义版本：白名单从“停用名单”改为“允许名单”等语义变化时递增，强制旧翻译缓存失效 */
    private const val RULES_VERSION = 2

    fun fingerprint(): String {
        val sb = StringBuilder()
        sb.append("v$RULES_VERSION|")
        sb.append(if (SettingsManager.devTranslationBlockTag) '1' else '0')
        sb.append(if (SettingsManager.devTranslationBlockResource) '1' else '0')
        sb.append(if (SettingsManager.devTranslationBlockMemory) '1' else '0')
        sb.append(if (SettingsManager.devTranslationBlockVariable) '1' else '0')
        sb.append('\u0001').append(DisabledWords.words().sorted().joinToString("\u0002"))
        sb.append('\u0001').append(ResourceNameWhitelist.names().sorted().joinToString("\u0002"))
        return sb.toString()
    }
}
