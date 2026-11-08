package app.kairos.mobile.ui.talk

import app.kairos.mobile.data.KairosApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConversationTest {
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // Lent, pour que deux sondages se chevauchent ; rien de neuf après la réponse.
                    val body = if (request.url.queryParameter("after") == "1") "[]"
                    else """[{"id": 1, "at": "2026-11-08T08:00:00", "role": "kairos", "source": "kairos", "text": "réponse"}]"""
                    return MockResponse.Builder().body(body).headersDelay(200, TimeUnit.MILLISECONDS).build()
                }
            }
            start()
        }
    }

    @After fun stop() { server.close() }

    @Test
    fun concurrent_polls_fire_reply_once() = runBlocking {
        val replies = AtomicInteger()
        val api = KairosApi(server.url("/").toString(), "t", OkHttpClient())
        val conversation = Conversation(api, CoroutineScope(Dispatchers.Default), {}) { _, _ -> replies.incrementAndGet() }
        (1..3).map { async(Dispatchers.Default) { conversation.poll() } }.awaitAll()
        assertEquals(1, replies.get())
        assertEquals(listOf(1L), conversation.entries.map { it.id })
    }

    @Test
    fun input_capped_like_server() {
        assertEquals("abc", capInput("abc"))
        assertEquals(8000, capInput("é".repeat(8010)).length)
        // Le serveur compte des caractères Unicode, pas des unités UTF-16 : un emoji n'est jamais coupé.
        val emoji = capInput("😀".repeat(8001))
        assertEquals(8000, emoji.codePointCount(0, emoji.length))
    }

    @Test
    fun pending_bubble_matches_server_cut() {
        val api = KairosApi(server.url("/").toString(), "t", OkHttpClient())
        val conversation = Conversation(api, CoroutineScope(Dispatchers.Default), {}) { _, _ -> }
        conversation.send("x".repeat(9000), handsFree = false)
        assertEquals(listOf("x".repeat(8000)), conversation.pending)
    }
}
