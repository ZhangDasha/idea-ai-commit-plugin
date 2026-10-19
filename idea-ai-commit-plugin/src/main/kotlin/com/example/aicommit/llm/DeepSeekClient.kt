package com.example.aicommit.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.progress.ProgressManager
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** OpenAI-compatible SSE client. Both headers and body subscriptions can be cancelled. */
open class DeepSeekClient(
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val checkCanceled: () -> Unit = { ProgressManager.checkCanceled() }
) {
    fun parseSseLine(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") return null
        val json = objectMapper.readTree(payload)
        require(!json.hasNonNull("error")) { "模型流式响应包含错误" }
        val choice = json.path("choices").path(0)
        require(choice.path("finish_reason").asText() != "length") { "模型输出被 token 上限截断，请精简输入或调整输出预算" }
        val content = choice.path("delta").path("content")
        // Reasoning/control frames often carry JSON null; whitespace text is a real streamed token.
        return if (content.isTextual) content.textValue().ifEmpty { null } else null
    }

    open fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String? = null,
        connectTimeout: Int = 10_000,
        readTimeout: Int = 60_000,
        onChunk: (String) -> Unit
    ) {
        require(connectTimeout > 0 && readTimeout > 0) { "请求超时必须大于 0" }
        val baseUri = URI.create(baseUrl)
        require(baseUri.scheme in setOf("http", "https") && !baseUri.host.isNullOrBlank() &&
            baseUri.userInfo == null && baseUri.fragment == null) { "Base URL 必须为 HTTP(S) 地址" }
        // Support the base URL convention used by OpenAI SDKs as well as a complete endpoint.
        val basePath = baseUri.rawPath.orEmpty().trimEnd('/')
        val completionPath = if (basePath.endsWith("/chat/completions")) basePath else "$basePath/chat/completions"
        val query = baseUri.rawQuery?.let { "?$it" }.orEmpty()
        val uri = URI.create("${baseUri.scheme}://${baseUri.rawAuthority}$completionPath$query")
        checkCanceled()
        val body = mutableMapOf<String, Any>(
            "model" to model,
            "stream" to true,
            "temperature" to 0.2,
            "max_tokens" to 2_048,
            "messages" to buildList {
                if (!systemPrompt.isNullOrBlank()) add(mapOf("role" to "system", "content" to systemPrompt))
                add(mapOf("role" to "user", "content" to prompt))
            }
        )
        if (uri.host.equals("api.deepseek.com", ignoreCase = true)) {
            // Structured commit summaries do not need a reasoning stream that consumes the output budget.
            body["thinking"] = mapOf("type" to "disabled")
        }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeout.toLong()))
            .followRedirects(HttpClient.Redirect.NEVER).build()
        val request = HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofMillis(connectTimeout.toLong() + readTimeout))
            .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body))).build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher())
        val subscriber = StreamSubscriber()
        try {
            val headers = awaitHeaders(response, connectTimeout.toLong() + readTimeout)
            headers.body().subscribe(subscriber)
            if (headers.statusCode() !in 200..299) {
                // Providers may echo source or credentials in error bodies; keep UI errors bounded.
                val status = headers.statusCode()
                val hint = if (status in setOf(400, 404))
                    "。请核对接口地址及模型 ID（$model）；模型展示名称可能与 API ID 不同" else ""
                throw IllegalStateException("模型请求失败，HTTP $status$hint")
            }
            val pendingLine = ByteArrayOutputStream()
            var lastRead = System.nanoTime()
            var done = false
            var outputChars = 0
            fun readLine() {
                val line = pendingLine.toString(Charsets.UTF_8).trimEnd('\r')
                pendingLine.reset()
                if (line.startsWith("data:") && line.removePrefix("data:").trim() == "[DONE]") done = true
                else parseSseLine(line)?.let { content ->
                    outputChars += content.length
                    require(outputChars <= MAX_OUTPUT_CHARS) { "模型输出过长，请精简提示词" }
                    onChunk(content)
                }
            }
            while (!done) {
                checkCanceled()
                val batch = subscriber.chunks.poll(50, TimeUnit.MILLISECONDS)
                if (batch != null) {
                    lastRead = System.nanoTime()
                    for (buffer in batch) {
                        while (buffer.hasRemaining() && !done) {
                            val byte = buffer.get()
                            if (byte == '\n'.code.toByte()) readLine() else pendingLine.write(byte.toInt())
                            require(pendingLine.size() <= MAX_SSE_LINE_BYTES) { "模型流式响应过长" }
                        }
                    }
                    if (!done) subscriber.subscription.get()?.request(1)
                }
                subscriber.failure.get()?.let { throw it }
                if (subscriber.completed.isDone && subscriber.chunks.isEmpty()) {
                    if (!done && pendingLine.size() > 0) readLine()
                    break
                }
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastRead) >= readTimeout) {
                    throw SocketTimeoutException("模型流式响应读取超时")
                }
            }
            checkCanceled()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw com.intellij.openapi.progress.ProcessCanceledException(e)
        } finally {
            subscriber.cancel()
            response.cancel(true)
        }
    }

    private fun awaitHeaders(
        response: CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>>,
        timeoutMillis: Long
    ): HttpResponse<Flow.Publisher<List<ByteBuffer>>> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            checkCanceled()
            try {
                return response.get(50, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (System.nanoTime() >= deadline) throw SocketTimeoutException("模型响应读取超时")
            } catch (e: ExecutionException) {
                throw (e.cause ?: e)
            }
        }
    }

    private class StreamSubscriber : Flow.Subscriber<List<ByteBuffer>> {
        val chunks = ArrayBlockingQueue<List<ByteBuffer>>(2)
        val subscription = AtomicReference<Flow.Subscription>()
        val failure = AtomicReference<Throwable>()
        val completed = CompletableFuture<Unit>()
        private val cancelled = java.util.concurrent.atomic.AtomicBoolean()

        override fun onSubscribe(value: Flow.Subscription) {
            if (!subscription.compareAndSet(null, value) || cancelled.get()) value.cancel() else value.request(1)
        }
        override fun onNext(item: List<ByteBuffer>) {
            // At most one requested batch is outstanding, so this offer never blocks an HTTP thread.
            if (!chunks.offer(item)) {
                failure.set(IllegalStateException("模型流式响应超出缓冲区"))
                cancel()
            }
        }
        override fun onError(error: Throwable) { failure.set(error); completed.complete(Unit) }
        override fun onComplete() { completed.complete(Unit) }
        fun cancel() { cancelled.set(true); subscription.get()?.cancel() }
    }

    companion object {
        private const val MAX_SSE_LINE_BYTES = 1_000_000
        private const val MAX_OUTPUT_CHARS = 16_000
    }
}
