package com.example.aicommit.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * 统一通知封装
 */
object AiCommitNotifications {

    private val group by lazy {
        NotificationGroupManager.getInstance().getNotificationGroup("AI Commit Notifications")
    }

    fun warn(project: Project, content: String) {
        group.createNotification(content, NotificationType.WARNING).notify(project)
    }

    fun error(project: Project, content: String) {
        group.createNotification("AI Commit 生成失败", content, NotificationType.ERROR).notify(project)
    }
}
