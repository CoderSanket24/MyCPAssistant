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
import java.util.concurrent.Executors

/**
 * Project-level service that owns the lifecycle of the embedded HTTP server.
 *
 * **Lifecycle**
 *  - The server is **started** by [CompanionServerStartupActivity] after the project fully loads.
 *  - The server is **stopped** automatically when IntelliJ disposes this service (project close).
 *
 * **Threading**
 *  - The server runs on a single *daemon* thread (won't block JVM shutdown).
 *  - Incoming requests are handled on that thread; heavy work is delegated to
 *    [ProblemFileGenerator.generate], which schedules VFS writes on IntelliJ's write thread.
 *
 * **Port selection**
 *  - The port is read from [AppSettingsState] at start time.
 *  - If the configured port is busy, a warning is logged and no server is started.
 */
@Service(Service.Level.PROJECT)
class CompanionServerService(private val project: Project) : Disposable {

    private val log  = thisLogger()
    private val gson = Gson()

    @Volatile
    private var server: HttpServer? = null

    // ── Public API ─────────────────────────────────────────────────────────

    /** Starts the HTTP server. Idempotent – calling twice has no effect. */
    fun start() {
        if (server != null) return

        val port = AppSettingsState.getInstance().listenerPort
        try {
            val httpServer = HttpServer.create(InetSocketAddress(port), /* backlog = */ 0)
            httpServer.createContext("/", ::handleRequest)
            // Single daemon thread so we don't block JVM exit
            httpServer.executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "MyCPAssistant-HTTP").also { it.isDaemon = true }
            }
            httpServer.start()
            server = httpServer
            log.info("MyCPAssistant: HTTP server started on port $port")
        } catch (ex: IOException) {
            log.warn("MyCPAssistant: Could not start HTTP server on port $port – ${ex.message}")
        }
    }

    // ── Disposable ─────────────────────────────────────────────────────────

    /**
     * Called by IntelliJ when the project is closed (or the plugin is unloaded).
     * Stops the server with a short grace period so in-flight requests can finish.
     */
    override fun dispose() {
        server?.stop(/* delaySeconds = */ 1)
        server = null
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
