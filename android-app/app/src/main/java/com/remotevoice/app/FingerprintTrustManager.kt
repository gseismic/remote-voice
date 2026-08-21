package com.remotevoice.app

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * 证书指纹固定 TrustManager（设计文档 §5）。
 * 信任根是用户配置的服务端证书 SHA-256 指纹（64 位 hex），
 * 不依赖系统 CA、不做主机名校验——指纹匹配即身份成立。
 */
class FingerprintTrustManager(expectedFingerprintHex: String) : X509TrustManager {

    private val expected = expectedFingerprintHex.replace(":", "").replace(" ", "").trim().lowercase()

    init {
        require(expected.length == 64 && expected.all { it.isDigit() || it in 'a'..'f' }) {
            "指纹格式错误：应为 64 位 hex"
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("本应用仅作为客户端，不接受客户端证书校验请求")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) {
            throw CertificateException("服务端未提供证书")
        }
        val actual = fingerprintOf(chain[0].encoded)
        if (actual != expected) {
            throw CertificateException("证书指纹不符！期望=$expected 实际=$actual")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /** 计算证书 DER 编码的 SHA-256 hex 指纹（与 relay 启动时打印格式一致）。 */
        fun fingerprintOf(encoded: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(encoded)
                .joinToString("") { "%02x".format(it) }
    }
}
