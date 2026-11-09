package com.github.codersanket24.mycpassistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persistent state for MyCPAssistant.
 *
 * All fields are plain `var` properties (required by the XML serializer).
 * Defaults are intentionally conservative so the plugin works out of the box.
 *
 * Storage: `<IDE config dir>/options/MyCPAssistant.xml`
 */
@Service(Service.Level.APP)
@State(
    name = "MyCPAssistantSettings",
    storages = [Storage("MyCPAssistant.xml")],
)
class AppSettingsState : PersistentStateComponent<AppSettingsState> {

    // ── Configurable fields ────────────────────────────────────────────────

    /** Relative path inside the project where solution files are created. */
    var outputDirectory: String = "src"

    /** TCP port that the embedded HTTP server listens on. */
    var listenerPort: Int = 27121

    /**
     * File extension for generated solution files.
     * Supported values: ".java", ".kt", ".cpp"
     */
    var fileExtension: String = ".java"

    /**
     * Multi-line code template written to the generated solution file.
     *
     * Supported placeholders:
     *  - `${PROBLEM_NAME}`  – sanitised class/file name derived from the problem title
     *  - `${DATE}`          – current date in yyyy-MM-dd format
     *  - `${TIME_LIMIT}`    – time limit in milliseconds
     *  - `${MEMORY_LIMIT}`  – memory limit in megabytes
     *  - `${URL}`           – problem URL
     *  - `${GROUP}`         – contest / problem group
     */
    var codeTemplate: String = DEFAULT_TEMPLATE

    // ── PersistentStateComponent ───────────────────────────────────────────

    override fun getState(): AppSettingsState = this

    override fun loadState(state: AppSettingsState) {
        outputDirectory = state.outputDirectory
        listenerPort    = state.listenerPort
        fileExtension   = state.fileExtension
        codeTemplate    = state.codeTemplate
    }

    // ── Companion ──────────────────────────────────────────────────────────

    companion object {
        /** Retrieve the singleton instance from the application service container. */
        fun getInstance(): AppSettingsState =
            ApplicationManager.getApplication().getService(AppSettingsState::class.java)

        private val DEFAULT_TEMPLATE = """
            import java.util.Scanner;

            /**
             * Problem : ${'$'}{PROBLEM_NAME}
             * Group   : ${'$'}{GROUP}
             * URL     : ${'$'}{URL}
             * Limits  : ${'$'}{TIME_LIMIT} ms / ${'$'}{MEMORY_LIMIT} MB
             * Date    : ${'$'}{DATE}
             */
            public class ${'$'}{PROBLEM_NAME} {
                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                    // TODO: solve
                }
            }
        """.trimIndent()
    }
}
