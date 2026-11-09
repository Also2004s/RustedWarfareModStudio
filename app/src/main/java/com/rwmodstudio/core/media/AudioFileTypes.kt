package com.rwmodstudio.core.media

/**
 * 音频文件类型判断（文件浏览器 / 打开路由共用）。
 *
 * 只收 MediaPlayer 原生可播放的常见容器：
 * ogg / oga（Vorbis）、wav、mp3、flac（API 21+）、m4a / aac、opus。
 */
val AUDIO_EXTENSIONS: Set<String> = setOf(
    "ogg", "oga", "wav", "mp3", "flac", "m4a", "aac", "opus"
)

/** 按文件名（或路径）后缀判断是否为音频文件，大小写不敏感。 */
fun isAudioFileName(nameOrPath: String): Boolean {
    val ext = nameOrPath.substringAfterLast('.', "").substringBefore('?').lowercase()
    return ext.isNotEmpty() && ext in AUDIO_EXTENSIONS
}
