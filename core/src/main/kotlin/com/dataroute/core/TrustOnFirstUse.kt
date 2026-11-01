package com.dataroute.core

import java.security.MessageDigest

/**
 * Trust-on-first-use for home devices' self-signed certificates (NVR, NAS, Home Assistant…), like SSH host keys:
 * the first certificate seen for host:port is remembered; later the same one is accepted and any other is refused.
 * That stops a device on someone else's network from posing as "192.168.1.10" and collecting your login.
 */
class TrustOnFirstUse(private val load: (String) -> String?, private val save: (String, String) -> Unit) {
    enum class Verdict { FIRST_USE_TRUSTED, MATCHES, CHANGED }

    fun check(host: String, port: Int, certificateDer: ByteArray): Verdict {
        val key = "${host.lowercase()}:$port"
        val fingerprint = fingerprint(certificateDer)
        val known = load(key)
        return when {
            known == null -> { save(key, fingerprint); Verdict.FIRST_USE_TRUSTED }
            known == fingerprint -> Verdict.MATCHES
            else -> Verdict.CHANGED
        }
    }

    companion object {
        fun fingerprint(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
    }
}
