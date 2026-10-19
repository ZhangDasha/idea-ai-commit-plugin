package com.example.aicommit.action

import com.example.aicommit.git.GitDiffCollector
import com.example.aicommit.service.CommitGenerationRequest
import com.example.aicommit.service.CommitMessageGenerationService
import com.example.aicommit.settings.AiCommitSettingsService
import com.example.aicommit.ui.AiCommitNotifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.ui.CommitMessage
import java.util.concurrent.atomic.AtomicBoolean

/** Commit toolbar entry; all collection/network work is protected by the task lifecycle. */
class GenerateCommitMessageAction : AnAction() {
    private val log = Logger.getInstance(GenerateCommitMessageAction::class.java)
    private val isGenerating = AtomicBoolean(false)
    private val generationService = CommitMessageGenerationService()
    private val gitDiffCollector = GitDiffCollector()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null &&
            e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) is CommitMessage &&
            e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) != null && !isGenerating.get()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? CommitMessage ?: return
        val workflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        val changes = workflowUi.getIncludedChanges().toList()
        val unversioned = workflowUi.getIncludedUnversionedFiles().toList()
        if (changes.isEmpty() && unversioned.isEmpty()) {
            AiCommitNotifications.warn(project, "未选择变更文件")
            return
        }
        val settingsService = service<AiCommitSettingsService>()
        // Snapshot settings before leaving EDT; subsequent edits affect the next request.
        val state = settingsService.state.copy()
        val configError = settingsService.validate(state, "pending PasswordSafe lookup")
        if (configError != null) {
            AiCommitNotifications.warn(project, configError)
            return
        }
        if (!isGenerating.compareAndSet(false, true)) return
        val messageDocument = commitMessage.editorField.document
        val writeback = CommitMessageWriteback({ messageDocument.text }, commitMessage::setCommitMessage)
        writeback.update("正在读取 ${changes.size + unversioned.size} 个所选变更…")
        log.info("AI Commit started: language=${state.language}, tracked=${changes.size}, untracked=${unversioned.size}")

        val task = object : Task.Backgroundable(project, "生成提交信息", true) {
            private var result: String? = null
            private var finished = false
            private var phase = "检查配置"

            override fun run(indicator: ProgressIndicator) {
                indicator.text = phase
                ProgressManager.checkCanceled()
                // PasswordSafe may block or show a prompt; do not read it on EDT.
                val apiKey = settingsService.getApiKey()
                settingsService.validate(state, apiKey)?.let { throw IllegalArgumentException(it) }
                phase = "读取所选变更"
                indicator.text = phase
                val diff = gitDiffCollector.collectStructured(
                    project, changes, unversioned, state.maxFiles, state.maxHunksPerFile
                )
                log.info("AI Commit collected: files=${diff.files.size}/${diff.totalFiles}, hunks=${diff.keyHunks.size}, truncated=${diff.truncated}")
                require(diff.files.isNotEmpty()) { "所选变更没有可分析的内容，请检查文件过滤或部分提交选择" }
                val request = CommitGenerationRequest(
                    structuredDiff = diff,
                    language = state.language,
                    baseUrl = state.baseUrl,
                    apiKey = apiKey,
                    analysisModel = state.analysisModel,
                    composerModel = state.composerModel,
                    confidenceThreshold = state.confidenceThreshold,
                    connectTimeout = state.connectTimeoutMillis,
                    readTimeout = state.readTimeoutMillis,
                    analysisPromptTemplate = state.analysisPromptTemplate,
                    composerPromptTemplate = state.composerPromptTemplate
                )
                phase = "分析与生成提交信息"
                indicator.text = phase
                val buffer = StringBuilder()
                result = generationService.generate(request) { chunk ->
                    ProgressManager.checkCanceled()
                    buffer.append(chunk)
                    val snapshot = buffer.toString()
                    ApplicationManager.getApplication().invokeLater {
                        if (!finished && !project.isDisposed && !indicator.isCanceled) {
                            writeback.update(snapshot)
                        }
                    }
                }
                ProgressManager.checkCanceled()
            }

            override fun onSuccess() {
                log.info("AI Commit completed: language=${state.language}, headerChars=${result?.lineSequence()?.firstOrNull()?.length ?: 0}")
                finish { result?.let(writeback::update) }
            }

            override fun onCancel() {
                log.info("AI Commit canceled at $phase")
                finish { writeback.restore() }
            }

            override fun onThrowable(error: Throwable) {
                finish {
                    writeback.restore()
                    if (error !is ProcessCanceledException) {
                        log.warn("AI Commit failed at $phase: ${error.javaClass.simpleName}; no new validated result written")
                        AiCommitNotifications.error(project,
                            "本次未生成新提交信息，原有内容已保留。\n阶段：$phase\n${error.message ?: "生成失败"}")
                    }
                }
            }

            private fun finish(update: () -> Unit) {
                finished = true
                try {
                    if (!project.isDisposed) update()
                } finally {
                    isGenerating.set(false)
                }
            }
        }
        try {
            ProgressManager.getInstance().run(task)
        } catch (error: Exception) {
            writeback.restore()
            isGenerating.set(false)
            if (error !is ProcessCanceledException) AiCommitNotifications.error(project, error.message ?: "无法启动生成任务")
        }
    }
}
