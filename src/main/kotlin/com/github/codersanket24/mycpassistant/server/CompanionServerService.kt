package com.github.codersanket24.mycpassistant.server

import com.github.codersanket24.mycpassistant.generator.ProblemFileGenerator
import com.github.codersanket24.mycpassistant.model.ProblemData
import com.github.codersanket24.mycpassistant.settings.AppSettingsState
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Project-level service that owns the lifecycle of the embedded HTTP server.
 *
 * **Lifecycle**
 *  - Started by [com.github.codersanket24.mycpassistant.startup.CompanionServerStartupActivity]
 *    after the project loads.
 *  - Stopped automatically when IntelliJ disposes this service (project close).
 */
@Service(Service.Level.PROJECT)
class CompanionServerService(private val project: Project) : Disposable {

    private val log  = thisLogger()
    private val gson = Gson()

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var executor: ExecutorService? = null

    // ── Public API ─────────────────────────────────────────────────────────

    /** Starts the HTTP server. Idempotent – calling twice has no effect. */
    @Synchronized
    fun start() {
        if (server != null) return

        val port = AppSettingsState.getInstance().listenerPort
        try {
            val httpServer = HttpServer.create(InetSocketAddress(port), /* backlog = */ 0)
            httpServer.createContext("/", ::handleRequest)

            val exec = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "MyCPAssistant-HTTP").also { it.isDaemon = true }
            }
            executor = exec
            httpServer.executor = exec

            httpServer.start()
            server = httpServer
            log.info("MyCPAssistant: HTTP server started on port $port")
        } catch (ex: IOException) {
            log.warn("MyCPAssistant: Could not start HTTP server on port $port – ${ex.message}")
        }
    }

    /** Restarts the HTTP server (e.g. after port changes in settings). */
    @Synchronized
    fun restart() {
        dispose()
        start()
    }

    // ── Disposable ─────────────────────────────────────────────────────────

    @Synchronized
    override fun dispose() {
        server?.stop(/* delaySeconds = */ 1)
        server = null
        executor?.shutdownNow()
        executor = null
        log.info("MyCPAssistant: HTTP server stopped.")
    }

    // ── Request handler ────────────────────────────────────────────────────

    private fun handleRequest(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod.uppercase()) {
                "POST" -> handlePost(exchange)
                else   -> sendResponse(exchange, 405, "Method Not Allowed")
            }
        } catch (ex: Exception) {
            log.error("MyCPAssistant: Unhandled error in HTTP handler", ex)
            runCatching { sendResponse(exchange, 500, "Internal Server Error") }
        }
    }

    private fun handlePost(exchange: HttpExchange) {
        val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).readText()

        val problem: ProblemData = try {
            gson.fromJson(body, ProblemData::class.java)
        } catch (ex: JsonSyntaxException) {
            log.warn("MyCPAssistant: Malformed JSON from Competitive Companion – ${ex.message}")
            sendResponse(exchange, 400, "Bad Request – invalid JSON")
            return
        }

        if (problem.name.isBlank()) {
            sendResponse(exchange, 422, "Unprocessable – 'name' field is missing")
            return
        }

        log.info("MyCPAssistant: Received problem '${problem.name}' with ${problem.tests.size} test(s)")
        sendResponse(exchange, 200, "OK")   // ACK immediately; generation is asynchronous

        ProblemFileGenerator.generate(project, problem)
    }

    private fun sendResponse(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
