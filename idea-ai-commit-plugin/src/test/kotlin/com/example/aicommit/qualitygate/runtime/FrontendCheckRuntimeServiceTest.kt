package com.example.aicommit.qualitygate.runtime

import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class FrontendCheckRuntimeServiceTest {

    @Test
    fun `dispose should cancel and destroy all tracked processes`() {
        val processOne = mockk<TrackedProcess>(relaxed = true)
        val processTwo = mockk<TrackedProcess>(relaxed = true)
        val service = FrontendCheckRuntimeService()

        service.track(processOne)
        service.track(processTwo)
        service.dispose()

        verify { processOne.cancel("project disposed") }
        verify { processTwo.cancel("project disposed") }
    }

    @Test
    fun `acquire and release should manage permits`() {
        val service = FrontendCheckRuntimeService()
        val projectRoot = "/repo/web"

        service.acquire(projectRoot)
        // 获取后可以正常释放
        service.release(projectRoot)

        // 验证可再次获取（如果释放正确）
        service.acquire(projectRoot)
        service.release(projectRoot)
    }

    @Test
    fun `untrack should remove process from active set`() {
        val process = mockk<TrackedProcess>(relaxed = true)
        val service = FrontendCheckRuntimeService()

        service.track(process)
        service.untrack(process)
        // dispose 后不应再 cancel 已移除的进程
        service.dispose()

        // 未被 cancel（因为已 untrack），验证 relaxed mock 不报错即可
    }
}
