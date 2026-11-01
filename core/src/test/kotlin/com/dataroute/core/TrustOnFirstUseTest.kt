package com.dataroute.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TrustOnFirstUseTest {
    @Test fun firstUseIsRememberedAndChangesAreRefused() {
        val store = HashMap<String, String>()
        val tofu = TrustOnFirstUse({ store[it] }, { k, v -> store[k] = v })
        val nvr = "certificate-of-the-real-nvr".toByteArray()
        val impostor = "certificate-of-an-impostor".toByteArray()

        assertEquals(TrustOnFirstUse.Verdict.FIRST_USE_TRUSTED, tofu.check("192.168.1.10", 443, nvr))
        assertEquals(TrustOnFirstUse.Verdict.MATCHES, tofu.check("192.168.1.10", 443, nvr))
        assertEquals(TrustOnFirstUse.Verdict.CHANGED, tofu.check("192.168.1.10", 443, impostor))
        // still refused after the attempt: the impostor never replaces the remembered certificate
        assertEquals(TrustOnFirstUse.Verdict.CHANGED, tofu.check("192.168.1.10", 443, impostor))
        // pins are per host and port, case-insensitive host
        assertEquals(TrustOnFirstUse.Verdict.FIRST_USE_TRUSTED, tofu.check("192.168.1.10", 8443, impostor))
        assertEquals(TrustOnFirstUse.Verdict.MATCHES, tofu.check("NAS.LAN".lowercase(), 5001, nvr).let { tofu.check("nas.lan", 5001, nvr) })
        assertEquals(95, TrustOnFirstUse.fingerprint(nvr).length) // 32 bytes as AA:BB:…
    }
}
