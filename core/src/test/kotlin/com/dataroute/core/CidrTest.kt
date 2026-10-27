package com.dataroute.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CidrTest {
    @Test fun parseAndFormat() {
        assertEquals("192.168.1.0/24", Cidr.parse("192.168.1.77/24").toString())
        assertEquals("10.0.0.5/32", Cidr.parse("10.0.0.5").toString())
        assertNull(Cidr.parse("300.1.1.1"))
        assertNull(Cidr.parse("1.2.3"))
        assertNull(Cidr.parse("1.2.3.4/33"))
        assertNull(Cidr.parse("abc"))
        assertEquals("1.2.3.4", Ip.format(Ip.parse("1.2.3.4")!!))
        assertEquals(-1062731510, Ip.parse("192.168.1.10")!!)
    }

    @Test fun parseList() {
        val (ok, bad) = Cidr.parseList("192.168.1.0/24, 10.1.1.1\n junk ;172.16.0.0/12")
        assertEquals(3, ok.size)
        assertEquals(listOf("junk"), bad)
    }

    @Test fun containsAndCovers() {
        val lan = Cidr.parse("192.168.0.0/16")!!
        assertTrue(lan.contains(Ip.parse("192.168.200.9")!!))
        assertFalse(lan.contains(Ip.parse("192.169.0.1")!!))
        assertTrue(lan.covers(Cidr.parse("192.168.5.0/24")!!))
        assertFalse(Cidr.parse("192.168.5.0/24")!!.covers(lan))
        assertTrue(lan.overlaps(Cidr.parse("192.168.5.0/24")!!))
        assertFalse(lan.overlaps(Cidr.parse("10.0.0.0/8")!!))
    }

    @Test fun complementOfNothingIsEverything() {
        assertEquals(listOf("0.0.0.0/0"), Cidr.complement(emptyList()).map { it.toString() })
    }

    @Test fun complementOfEverythingIsNothing() {
        assertTrue(Cidr.complement(listOf(Cidr.parse("0.0.0.0/0")!!)).isEmpty())
    }

    @Test fun complementIsExactOnRandomAddresses() {
        val excluded = (RoutePolicy.PRIVATE_RANGES + RoutePolicy.NEVER_TUNNEL + listOf("203.0.113.7", "100.64.1.0/24").map { Cidr.parse(it)!! })
        val routes = Cidr.complement(excluded)
        // routes are pairwise disjoint and never touch an exclusion
        for (r in routes) for (e in excluded) assertFalse(r.overlaps(e), "$r overlaps $e")
        for (i in routes.indices) for (j in i + 1 until routes.size) assertFalse(routes[i].overlaps(routes[j]))
        val rnd = Random(42)
        repeat(200_000) {
            val ip = rnd.nextInt()
            assertEquals(excluded.none { it.contains(ip) }, routes.any { it.contains(ip) }, Ip.format(ip))
        }
        // boundary addresses around the exclusions
        for (e in excluded) for (ip in listOf(e.network - 1, e.network, e.network + (1 shl (32 - e.prefix)) - 1, e.network + (1 shl (32 - e.prefix)))) {
            assertEquals(excluded.none { it.contains(ip) }, routes.any { it.contains(ip) }, Ip.format(ip))
        }
        assertNotNull(routes.firstOrNull())
        println("complement size = ${routes.size}")
    }

    @Test fun policy() {
        val nvr = Cidr.parse("192.168.1.10")!!
        val p = RoutePolicy(listOf(nvr, Cidr.parse("10.0.0.0/8")!!), forceCellular = setOf(Ip.parse("10.177.0.34")!!))
        assertEquals(Path.WIFI, p.pathFor(Ip.parse("192.168.1.10")!!))
        assertEquals(Path.WIFI, p.pathFor(Ip.parse("10.5.5.5")!!))
        assertEquals(Path.CELLULAR, p.pathFor(Ip.parse("10.177.0.34")!!))
        assertEquals(Path.CELLULAR, p.pathFor(Ip.parse("142.250.1.1")!!))
        assertEquals(Path.WIFI, RoutePolicy(emptyList(), internetViaWifi = true).pathFor(Ip.parse("1.1.1.1")!!))
    }
}

class LocalNetsTest {
    @Test fun buildsFromSettings() {
        val r = LocalNets.build(true, Cidr.parse("192.168.1.55/24"), "192.168.1.10", "172.20.0.0/16, 10.9.9.9")
        assertTrue(r.errors.isEmpty())
        assertTrue(r.nets.any { it.toString() == "192.168.1.10/32" })
        assertTrue(r.nets.any { it.toString() == "192.168.1.0/24" })
        assertTrue(r.nets.any { it.toString() == "172.20.0.0/16" })
        assertTrue(r.nets.any { it.toString() == "10.0.0.0/8" })
        val minimal = LocalNets.build(false, null, "", "")
        assertTrue(minimal.nets.isEmpty() && minimal.errors.isEmpty())
    }

    @Test fun reportsBadInput() {
        val r = LocalNets.build(false, null, "my-nvr.local", "bogus, 1.2.3.0/24")
        assertEquals(2, r.errors.size)
        assertEquals(1, r.nets.size) // the valid extra prefix is still kept
        val (ok, bad) = LocalNets.parseDns("1.1.1.1, 8.8.8.8 nope")
        assertEquals(2, ok.size); assertEquals(listOf("nope"), bad)
    }
}
