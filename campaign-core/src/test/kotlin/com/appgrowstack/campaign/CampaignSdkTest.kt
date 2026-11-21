package com.appgrowstack.campaign

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CampaignSdkTest {
    /** packages/contract の APPGROWSTACK_CLIENT_PATTERN と同じ */
    private val clientPattern = Regex("""^(ios|android)/\d+\.\d+(\.\d+)?/[a-z][a-z0-9-]{1,40}/\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""")

    @Test
    fun headerFollowsTheContract() {
        val value = CampaignSdk.client("android", "2.1.0")
        assertEquals("android/2.1.0/kotlin-app-campaign/${CampaignSdk.VERSION}", value)
        assertTrue(clientPattern.matches(value))
        assertTrue(clientPattern.matches(CampaignSdk.client("android", "2.1")))
    }

    @Test
    fun versionMatchesGradleProperties() {
        val properties = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "gradle.properties") }
            .first { it.isFile && it.readText().contains(Regex("(?m)^version=")) }
        val version = Regex("(?m)^version=(.+)$").find(properties.readText())!!.groupValues[1].trim()
        assertEquals(version, CampaignSdk.VERSION)
    }

    @Test
    fun fetcherSendsTheHeader() = runTest {
        val seen = mutableListOf<Pair<String?, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            synchronized(seen) { seen += exchange.requestHeaders.getFirst("AppGrowStack-Client") to exchange.requestURI.path }
            exchange.sendResponseHeaders(304, -1)
            exchange.close()
        }
        server.start()
        try {
            val fetcher = HttpCampaignSetFetcher(
                "pk_KUdMabcdefghijklmnopqrst",
                CampaignSdk.client("android", "2.1.0"),
                endpoint = "http://127.0.0.1:${server.address.port}/v1/campaigns/sets/",
            )
            assertEquals(CampaignFetchResult.NotModified, fetcher.fetch("\"v4\""))
            assertEquals(
                listOf("android/2.1.0/kotlin-app-campaign/${CampaignSdk.VERSION}" to "/v1/campaigns/sets/pk_KUdMabcdefghijklmnopqrst"),
                synchronized(seen) { seen.toList() },
            )
        } finally {
            server.stop(0)
        }
    }
}
