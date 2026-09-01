package com.rwmodstudio.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore 的 AES-256-GCM 字符串加解密。
 *
 * 用途：敏感配置（如 AI API Key）在 [com.rwmodstudio.core.FileSettings]（外部
 * settings.json）中以密文形式存储，避免明文落盘被其它应用读取。
 *
 * - 密钥绑定设备 + 应用签名，存于 Keystore 不落盘；
 * - 加密结果 = base64(IV(12B) + 密文)，IV 由系统在每次加密时随机生成；
 * - Keystore 不可用时回退明文（前缀 [PLAIN_PREFIX] 标记），保证可用性；
 * - 解密失败由调用方处理（回退默认值）。
 */
object CryptoUtil {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rwmod_ai_key"
    private const val GCM_IV_LENGTH = 12 // 字节
    private const val GCM_TAG_BITS = 128

    /** 明文兜底前缀：Keystore 不可用时 encrypt 返回此前缀 + 原文 */
    private const val PLAIN_PREFIX = "PLAIN:"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * 加密明文，返回 base64(IV + 密文)；
     * Keystore 不可用时回退 [PLAIN_PREFIX] + 原文（保证可用，牺牲保密）。
     */
    fun encrypt(plain: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + enc, Base64.NO_WRAP)
        } catch (e: Exception) {
            PLAIN_PREFIX + plain
        }
    }

    /**
     * 解密 [encrypt] 的输出；失败抛异常（调用方应回退默认值并清缓存）。
     */
    fun decrypt(encBase64: String): String {
        if (encBase64.startsWith(PLAIN_PREFIX)) {
            return encBase64.removePrefix(PLAIN_PREFIX)
        }
        val data = Base64.decode(encBase64, Base64.DEFAULT)
        require(data.size > GCM_IV_LENGTH) { "密文过短，无法解出 IV" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, data.copyOfRange(0, GCM_IV_LENGTH))
        )
        return String(cipher.doFinal(data.copyOfRange(GCM_IV_LENGTH, data.size)), Charsets.UTF_8)
    }
}
