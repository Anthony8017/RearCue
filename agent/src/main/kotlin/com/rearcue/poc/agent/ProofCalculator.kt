package com.rearcue.poc.agent

import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 远控配对 proof 计算（E1 实测公式，生产中继已验证——docs/poc-logs/20260927-e1-relay-spike.md）。
 *
 * ```
 * proof = HMAC-SHA256(key = UTF8(pass_hash 原串), msg = "<nonce>|<role>|<device_sid>").base64url无填充
 * ```
 *
 * 生产向量（capture 20260927-135526，一次性随机凭据、会话已销毁）钉死在单测。
 */
object ProofCalculator {
    private const val HMAC_SHA256 = "HmacSHA256"

    fun proof(passHash: String, nonce: String, role: String, deviceSid: String): String {
        val message = "$nonce|$role|$deviceSid"
        return hmacSha256Base64Url(passHash.toByteArray(Charsets.UTF_8), message.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Base64Url(key: ByteArray, message: ByteArray): String {
        val mac = try {
            Mac.getInstance(HMAC_SHA256)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("HmacSHA256 unavailable", e)
        }
        try {
            mac.init(SecretKeySpec(key, HMAC_SHA256))
        } catch (e: InvalidKeyException) {
            throw IllegalStateException("invalid HMAC key", e)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message))
    }
}
