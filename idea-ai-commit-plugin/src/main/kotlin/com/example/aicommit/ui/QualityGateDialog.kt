package com.example.aicommit.ui

import com.example.aicommit.qualitygate.model.FrontendCheckSessionResult
import com.example.aicommit.qualitygate.model.FrontendCheckStatus
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * 前端质量闸口确认弹窗
 *
 * 显示检查摘要和详情，提供三个操作：
 * - 取消生成
 * - 继续生成
 * - 打开设置
 */
class QualityGateDialog(
    project: Project,
    private val result: FrontendCheckSessionResult
) : DialogWrapper(project) {

    private val myProject = project

    private val summaryArea = JBTextArea()
    private val detailsArea = JBTextArea()

    init {
        title = "Frontend Check Warnings"
        summaryArea.isEditable = false
        detailsArea.isEditable = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(4, 4))

        // 摘要区域：失败项目名 + 检查项 + 一行原因
        val failedChecks = result.projects.flatMap { projectResult ->
            projectResult.checks.filter { it.status != FrontendCheckStatus.PASSED }.map { check ->
                "${projectResult.projectName} / ${check.type}: ${check.summary}"
            }
        }
        summaryArea.text = failedChecks.joinToString("\n")
        summaryArea.rows = minOf(failedChecks.size, 8)

        val summaryScrollPane = JBScrollPane(summaryArea)
        summaryScrollPane.preferredSize = Dimension(500, 120)

        // 详情区域（默认折叠）
        val detailLines = result.projects.flatMap { projectResult ->
            projectResult.checks.filter { it.status != FrontendCheckStatus.PASSED }.map { check ->
                buildString {
                    appendLine("── ${projectResult.projectName} / ${check.type} ──")
                    appendLine("Command: ${check.command ?: "N/A"}")
                    appendLine("Exit Code: ${check.exitCode ?: "timeout"}")
                    appendLine("Duration: ${check.durationMillis}ms")
                    if (check.details.isNotBlank()) {
                        appendLine("Details:")
                        appendLine(check.details.take(2000))
                    }
                }
            }
        }
        detailsArea.text = detailLines.joinToString("\n")
        detailsArea.rows = 10
        val detailsScrollPane = JBScrollPane(detailsArea)
        detailsScrollPane.preferredSize = Dimension(500, 200)

        // 展开按钮
        val toggleDetailsBtn = JButton("Show Details")
        var detailsVisible = false
        detailsScrollPane.isVisible = false
        toggleDetailsBtn.addActionListener {
            detailsVisible = !detailsVisible
            detailsScrollPane.isVisible = detailsVisible
            toggleDetailsBtn.text = if (detailsVisible) "Hide Details" else "Show Details"
            panel.revalidate()
            panel.repaint()
            // 调整对话框大小
            pack()
        }

        panel.add(JLabel("以下前端检查项存在问题："), BorderLayout.NORTH)
        panel.add(summaryScrollPane, BorderLayout.CENTER)

        val southPanel = JPanel(BorderLayout())
        southPanel.add(toggleDetailsBtn, BorderLayout.NORTH)
        southPanel.add(detailsScrollPane, BorderLayout.CENTER)
        panel.add(southPanel, BorderLayout.SOUTH)

        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            cancelAction,  // 取消生成
            okAction,      // 继续生成
            object : DialogWrapperAction("打开设置") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(myProject, "AI Commit")
                }
            }
        )
    }

    /** 重写 OK 按钮文本 */
    override fun getOKAction(): Action {
        return super.getOKAction().apply {
            putValue(Action.NAME, "继续生成")
        }
    }

    /** 重写 Cancel 按钮文本 */
    override fun getCancelAction(): Action {
        return super.getCancelAction().apply {
            putValue(Action.NAME, "取消生成")
        }
    }
}
