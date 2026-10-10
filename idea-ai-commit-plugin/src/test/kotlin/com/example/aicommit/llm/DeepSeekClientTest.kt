package com.example.aicommit.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.progress.ProcessCanceledException
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekClientTest {
    private fun withServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit, test: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool { Thread(it, "test-http").apply { isDaemon = true } }
        server.executor = executor
        server.createContext("/", handler)
        server.start()
        try { test("http://127.0.0.1:${server.address.port}/chat/completions") }
        finally { server.stop(0); executor.shutdownNow() }
    }

    @Test fun `stream delivers content on calling thread and stops at DONE`() {
        val mapper = ObjectMapper()
        var request = ""
        withServer({ exchange ->
            request = exchange.requestBody.bufferedReader().use { it.readText() }
            val sse = "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"reasoning\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"fix:\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\" \"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"修复取消\"}}]}\n\n" +
                "data: [DONE]\n\ndata: not-json-after-done\n"
            val bytes = sse.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) { url ->
            val caller = Thread.currentThread()
            val chunks = mutableListOf<String>()
            DeepSeekClient(checkCanceled = {}).streamChat(url, "secret", "model", "prompt", "system", 1000, 1000) {
                assertEquals(caller, Thread.currentThread())
                chunks.add(it)
            }
            assertEquals(listOf("fix:", " ", "修复取消"), chunks)
            val json = mapper.readTree(request)
            assertEquals("model", json["model"].asText())
            assertEquals(0.2, json["temperature"].asDouble())
            assertEquals("system", json["messages"][0]["content"].asText())
            assertEquals("prompt", json["messages"][1]["content"].asText())
        }
    }

    @Test fun `SSE preserves text whitespace and ignores null control frames`() {
        val client = DeepSeekClient(checkCanceled = {})
        kotlin.test.assertNull(client.parseSseLine("data: {\"choices\":[{\"delta\":{\"content\":null}}]}"))
        kotlin.test.assertNull(client.parseSseLine("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thought\"}}]}"))
        assertEquals("\n\n", client.parseSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"\\n\\n\"}}]}"))
        assertFailsWith<IllegalArgumentException> {
            client.parseSseLine("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}")
        }
    }

    @Test fun `HTTP failures do not expose provider response`() {
        withServer({ exchange ->
            val bytes = "secret-source-and-api-key".toByteArray()
            exchange.sendResponseHeaders(401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) { url ->
            val error = assertFailsWith<IllegalStateException> {
                DeepSeekClient(checkCanceled = {}).streamChat(url, "secret", "model", "prompt", readTimeout = 1000) {}
            }
            assertTrue(error.message.orEmpty().contains("401"))
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    @Test fun `root versioned base and complete endpoint use the correct request path`() {
        val paths = mutableListOf<String>()
        withServer({ exchange ->
            paths.add(exchange.requestURI.toString())
            exchange.requestBody.close()
            val bytes = "data: [DONE]\n\n".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) { full ->
            val root = full.removeSuffix("/chat/completions")
            val client = DeepSeekClient(checkCanceled = {})
            for (url in listOf(root, "$root/", "$root/v1", "$root/v1/", full, "$full/", "$root/v1/chat/completions?api-version=test")) {
                client.streamChat(url, "key", "model", "prompt", readTimeout = 1000) {}
            }
            assertEquals(listOf("/chat/completions", "/chat/completions", "/v1/chat/completions", "/v1/chat/completions",
                "/chat/completions", "/chat/completions", "/v1/chat/completions?api-version=test"), paths)
        }
    }

    @Test fun `invalid endpoint or model gives an actionable error without exposing response`() {
        withServer({ exchange ->
            exchange.requestBody.close()
            val bytes = "private-provider-response".toByteArray()
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) { url ->
            val error = assertFailsWith<IllegalStateException> {
                DeepSeekClient(checkCanceled = {}).streamChat(url, "key", "display-model-name", "prompt", readTimeout = 1000) {}
            }
            assertTrue(error.message.orEmpty().contains("模型 ID"))
            assertTrue(error.message.orEmpty().contains("display-model-name"))
            assertFalse(error.message.orEmpty().contains("private-provider-response"))
        }
    }

    @Test fun `configured read timeout reaches stalled response`() {
        withServer({ exchange ->
            exchange.requestBody.close()
            exchange.sendResponseHeaders(200, 0)
            try { CountDownLatch(1).await(5, TimeUnit.SECONDS) }
            finally { exchange.close() }
        }) { url ->
            assertFailsWith<SocketTimeoutException> {
                DeepSeekClient(checkCanceled = {}).streamChat(url, "key", "model", "prompt", readTimeout = 150) {}
            }
        }
    }

    @Test fun `cancellation interrupts a stalled response before read timeout`() {
        val started = CountDownLatch(1)
        withServer({ exchange ->
            exchange.requestBody.close()
            exchange.sendResponseHeaders(200, 0)
            started.countDown()
            try { CountDownLatch(1).await(5, TimeUnit.SECONDS) }
            finally { exchange.close() }
        }) { url ->
            val start = System.nanoTime()
            assertFailsWith<ProcessCanceledException> {
                DeepSeekClient(checkCanceled = {
                    if (started.count == 0L) throw ProcessCanceledException()
                }).streamChat(url, "key", "model", "prompt", readTimeout = 4000) {}
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500)
        }
    }
}
