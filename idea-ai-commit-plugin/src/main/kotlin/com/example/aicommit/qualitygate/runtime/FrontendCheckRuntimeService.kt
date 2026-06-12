package com.example.aicommit.qualitygate.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * 项目级运行时服务：管理外部进程生命周期、有界并发
 *
 * - 全局最大外部进程并发数 2
 * - 单项目同时运行的外部进程数 2
 * - 项目关闭时销毁所有受追踪进程
 */
@Service(Service.Level.PROJECT)
class FrontendCheckRuntimeService : Disposable {

    companion object {
        const val MAX_GLOBAL_PROCESSES: Int = 2
        const val MAX_PROCESSES_PER_PROJECT: Int = 2
    }

    // 全局并发信号量
    private val globalSemaphore = Semaphore(MAX_GLOBAL_PROCESSES)
    // 按项目根目录分配的并发信号量
    private val projectCounters = ConcurrentHashMap<String, Semaphore>()
    // 当前活跃的受追踪进程
    private val activeProcesses = Collections.synchronizedSet(mutableSetOf<TrackedProcess>())

    /** 注册追踪进程 */
    fun track(process: TrackedProcess) {
        activeProcesses.add(process)
    }

    /** 移除追踪进程 */
    fun untrack(process: TrackedProcess) {
        activeProcesses.remove(process)
    }

    /** 获取执行许可（全局 + 项目级双闸门） */
    fun acquire(projectRoot: String) {
        globalSemaphore.acquire()
        projectCounters.computeIfAbsent(projectRoot) { Semaphore(MAX_PROCESSES_PER_PROJECT) }.acquire()
    }

    /** 释放执行许可 */
    fun release(projectRoot: String) {
        projectCounters[projectRoot]?.release()
        globalSemaphore.release()
    }

    /** 项目关闭时取消并销毁所有受追踪进程 */
    override fun dispose() {
        val snapshot = synchronized(activeProcesses) { activeProcesses.toList() }
        snapshot.forEach { it.cancel("project disposed") }
        activeProcesses.clear()
    }
}
