package com.example.aicommit.action

import com.example.aicommit.git.GitDiffCollector
import com.example.aicommit.qualitygate.detect.FrontendProjectDetector
import com.example.aicommit.qualitygate.resolve.FrontendCheckCommandResolver
import com.example.aicommit.qualitygate.run.FrontendCheckRunner
import com.example.aicommit.qualitygate.runtime.FrontendCheckRuntimeService
import com.example.aicommit.qualitygate.service.FrontendQualityGateService
import com.example.aicommit.service.CommitGenerationRequest
import com.example.aicommit.service.CommitMessageGenerationService
import com.example.aicommit.settings.AiCommitSettingsService
import com.example.aicommit.ui.AiCommitNotifications
import com.example.aicommit.ui.QualityGateDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ui.CommitMessage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 提交面板 AI 按钮入口
 */
class GenerateCommitMessageAction : AnAction() {

    private val isGenerating = AtomicBoolean(false)
    private val generationService = CommitMessageGenerationService()
    private val gitDiffCollector = GitDiffCollector()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        val workflow = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER)
        e.presentation.isEnabledAndVisible = commitMessage != null && workflow != null && !isGenerating.get()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 获取 CommitMessage 控件
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? CommitMessage ?: return
        val workflow = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) ?: return

        // 读取配置
        val settingsService = service<AiCommitSettingsService>()
        val state = settingsService.state
        val apiKey = settingsService.getApiKey()

        // 校验配置
        val configError = settingsService.validate(state, apiKey)
        if (configError != null) {
            AiCommitNotifications.warn(project, configError)
            return
        }

        // 通过独立的 COMMIT_WORKFLOW_UI DataKey 获取勾选变更
        val workflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        val includedChanges: List<Change> = workflowUi.getIncludedChanges()
        val unversionedFiles: List<FilePath> = workflowUi.getIncludedUnversionedFiles()

        if (includedChanges.isEmpty() && unversionedFiles.isEmpty()) {
            AiCommitNotifications.warn(project, "未选择变更文件")
            return
        }

        if (!isGenerating.compareAndSet(false, true)) return

        val originalMessage = commitMessage.text
        commitMessage.setCommitMessage("Generating commit message...")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating commit message", true) {
            override fun run(indicator: ProgressIndicator) {
                val diff = gitDiffCollector.collectWithUnifiedDiff(project, includedChanges, unversionedFiles)

                // 前端质量闸口检查
                var warningSummary: String? = null
                if (state.frontendChecksEnabled) {
                    val gateService = createQualityGateService(settingsService, project)
                    val changedPaths = includedChanges.mapNotNull { it.virtualFile?.path } +
                        unversionedFiles.map { it.path }
                    val gateResult = gateService.evaluate(project, changedPaths)

                    // 严重问题弹窗确认
                    if (gateResult.requiresConfirmation) {
                        var accepted = false
                        ApplicationManager.getApplication().invokeAndWait {
                            accepted = QualityGateDialog(project, gateResult).showAndGet()
                        }
                        if (!accepted) {
                            ApplicationManager.getApplication().invokeLater {
                                commitMessage.setCommitMessage(originalMessage ?: "")
                                isGenerating.set(false)
                            }
                            return
                        }
                    }

                    // 附加 warning 摘要到 prompt
                    if (state.includeWarningSummaryInPrompt) {
                        warningSummary = gateResult.warningSummaryForPrompt
                    }
                }

                val buffer = StringBuilder()
                val request = CommitGenerationRequest(
                    diff = diff,
                    language = state.language,
                    baseUrl = state.baseUrl,
                    apiKey = apiKey,
                    model = state.model,
                    promptTemplate = state.promptTemplate,
                    connectTimeout = state.connectTimeoutMillis,
                    readTimeout = state.readTimeoutMillis,
                    warningSummary = warningSummary
                )

                try {
                    val finalMessage = generationService.generate(request) { chunk ->
                        buffer.append(chunk)
                        ApplicationManager.getApplication().invokeLater {
                            commitMessage.setCommitMessage(buffer.toString())
                        }
                    }

                    // 校验结果非空
                    val usable = generationService.ensureUsableMessage(finalMessage)
                    ApplicationManager.getApplication().invokeLater {
                        commitMessage.setCommitMessage(usable)
                    }
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        // 恢复原始内容
                        commitMessage.setCommitMessage(originalMessage ?: "")
                        AiCommitNotifications.error(project, ex.message ?: "生成提交说明失败")
                    }
                } finally {
                    isGenerating.set(false)
                }
            }
        })
    }

    /** 创建质量闸口服务 */
    private fun createQualityGateService(
        settingsService: AiCommitSettingsService,
        project: com.intellij.openapi.project.Project
    ): FrontendQualityGateService {
        val runtimeService = project.service<FrontendCheckRuntimeService>()
        return FrontendQualityGateService(
            detector = FrontendProjectDetector(),
            resolver = FrontendCheckCommandResolver(settingsService),
            runner = FrontendCheckRunner(runtimeService)
        )
    }
}
