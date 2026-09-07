package com.github.codersanket24.mycpassistant.startup

import com.github.codersanket24.mycpassistant.server.CompanionServerService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Hooks into IntelliJ's **post-startup** phase to start the Competitive Companion
 * HTTP server once a project has fully loaded.
 *
 * Using [ProjectActivity] (the modern replacement for `StartupActivity`) ensures:
 *  - The server starts **after** the project model is ready.
 *  - No deprecated APIs are used.
 *
 * The server itself is managed by [CompanionServerService], which implements
 * [com.intellij.openapi.Disposable] to guarantee clean shutdown on project close.
 */
class CompanionServerStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.service<CompanionServerService>().start()
    }
}
