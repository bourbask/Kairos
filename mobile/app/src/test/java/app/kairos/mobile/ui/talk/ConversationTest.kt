package app.kairos.mobile.ui.talk

import app.kairos.mobile.data.KairosApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Conversation sur un serveur qui répond toujours [code]. */
    private fun failing(code: Int, unauthorized: () -> Unit = {}): Pair<MockWebServer, Conversation> {
        val failing = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(code).body("{}").build()
            }
            start()
        }
        val api = KairosApi(failing.url("/").toString(), "t", OkHttpClient())
        return failing to Conversation(api, CoroutineScope(Dispatchers.Default), unauthorized) { _, _ -> }
    }

    private fun waitUntil(ok: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!ok()) delay(10) }
    }

    @Test
    fun edit_failed_keeps_existing_draft_and_message() {
        val (failing, conversation) = failing(503)
        conversation.send("message raté", handsFree = false)
        waitUntil { conversation.failed.isNotEmpty() }
        conversation.draft = "brouillon"
        conversation.edit(conversation.failed.single())
        // Aucun texte perdu : le brouillon reste, le message en échec s'y ajoute.
        assertTrue(conversation.draft.contains("brouillon"))
        assertTrue(conversation.draft.contains("message raté"))
        assertTrue(conversation.failed.isEmpty())
        failing.close()
    }

    @Test
    fun unauthorized_send_goes_back_to_the_input() {
        var signedOut = false
        val (failing, conversation) = failing(401) { signedOut = true }
        conversation.send("à garder", handsFree = false)
        waitUntil { signedOut && conversation.pending.isEmpty() }
        assertEquals("à garder", conversation.draft)
        failing.close()
    }

    @Test
    fun unauthorized_send_with_a_draft_stays_as_failed() {
        var signedOut = false
        val (failing, conversation) = failing(401) { signedOut = true }
        conversation.draft = "autre chose"
        conversation.send("à garder", handsFree = false)
        waitUntil { signedOut && conversation.pending.isEmpty() }
        assertEquals("autre chose", conversation.draft)
        assertEquals(listOf("à garder"), conversation.failed.map { it.text })
        failing.close()
    }
}
