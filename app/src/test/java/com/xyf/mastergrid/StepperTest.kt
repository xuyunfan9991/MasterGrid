package com.xyf.mastergrid

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 假相机：filters 是相机里真实的顺序，每次滑动走一格，两端不循环。 */
private class FakeCamera(val filters: List<String>, start: String, var signWrong: Boolean = false) : StepCamera {
    var pos = filters.indexOf(start)
    var swipes = 0
    var flips = 0

    override suspend fun name() = filters[pos]

    override suspend fun swipe(forward: Boolean): Boolean {
        swipes++
        pos = (pos + if (forward != signWrong) 1 else -1).coerceIn(filters.indices)
        return true
    }

    override suspend fun awaitChange(old: String) = filters[pos].takeIf { it != old }

    override fun flipSign() {
        flips++
        signWrong = !signWrong
    }
}

class StepperTest {
    private fun walk(stored: List<String>, cam: FakeCamera, target: String): Pair<String?, ListLearner> = runBlocking {
        val learner = ListLearner(stored)
        Stepper.walk(stored, target, cam, learner) to learner
    }

    @Test
    fun `reaches target on an up-to-date list`() {
        val list = listOf("a", "b", "c", "d", "e")
        val cam = FakeCamera(list, "a")
        val (err, learner) = walk(list, cam, "e")
        assertNull(err)
        assertEquals(4, cam.swipes)
        assertNull(learner.result())
    }

    @Test
    fun `passes through a newly imported filter instead of bouncing`() {
        // 复现 2026-09-30 的问题：「暖阳明艳」是扫描后新导入的，之前会在它和前一个之间来回切
        val stored = listOf("人文", "苦楝花开", "和光1", "和光2", "标准", "鲜艳")
        val camera = listOf("人文", "苦楝花开", "暖阳明艳", "和光1", "和光2", "标准", "鲜艳")
        val cam = FakeCamera(camera, "苦楝花开")
        val (err, learner) = walk(stored, cam, "和光2")
        assertNull(err)
        assertEquals(3, cam.swipes)
        assertEquals(camera, learner.result())
    }

    @Test
    fun `passes through a new filter going backward`() {
        val stored = listOf("a", "b", "c", "d")
        val camera = listOf("a", "b", "x", "c", "d")
        val cam = FakeCamera(camera, "d")
        val (err, learner) = walk(stored, cam, "a")
        assertNull(err)
        assertEquals(4, cam.swipes)
        assertEquals(camera, learner.result())
    }

    @Test
    fun `starting on an unknown filter heads to the start first`() {
        val stored = listOf("a", "b", "c")
        val camera = listOf("a", "b", "c", "x")
        val cam = FakeCamera(camera, "x")
        val (err, _) = walk(stored, cam, "a")
        assertNull(err)
        assertEquals(3, cam.swipes)
    }

    @Test
    fun `turns around when an unknown filter sits at the end`() {
        // 从末尾的陌生滤镜出发、目标在后面不可能，但目标在前面而先撞到一端要能掉头
        val stored = listOf("a", "b", "c")
        val camera = listOf("x", "a", "b", "c")
        val cam = FakeCamera(camera, "x")
        val (err, _) = walk(stored, cam, "c")
        assertNull(err)
        assertEquals(0, cam.flips) // 在陌生滤镜上滑不动，不应该改保存的手势方向
    }

    @Test
    fun `recalibrates a wrong swipe direction`() {
        val list = listOf("a", "b", "c", "d", "e")
        val cam = FakeCamera(list, "c", signWrong = true)
        val (err, _) = walk(list, cam, "e")
        assertNull(err)
        assertEquals(1, cam.flips)
        assertTrue(!cam.signWrong)
    }

    @Test
    fun `recalibrates when wrong direction hits the end`() {
        val list = listOf("a", "b", "c")
        val cam = FakeCamera(list, "a", signWrong = true)
        val (err, _) = walk(list, cam, "c")
        assertNull(err)
        assertTrue(!cam.signWrong)
    }

    @Test
    fun `reports a deleted target instead of bouncing`() {
        val stored = listOf("a", "b", "c", "d")
        val camera = listOf("a", "b", "d")
        val cam = FakeCamera(camera, "a")
        val (err, learner) = walk(stored, cam, "c")
        assertTrue(err!!.contains("已经没有"))
        assertEquals(camera, learner.result())
    }

    @Test
    fun `learns a deleted filter on the way`() {
        val stored = listOf("a", "b", "c", "d", "e")
        val camera = listOf("a", "b", "d", "e")
        val cam = FakeCamera(camera, "a")
        val (err, learner) = walk(stored, cam, "e")
        assertNull(err)
        assertEquals(camera, learner.result())
    }

    @Test
    fun `gives up on a hopelessly stale list`() {
        val stored = listOf("a", "b", "c")
        val camera = listOf("p", "q", "r", "s")
        val cam = FakeCamera(camera, "q")
        val (err, _) = walk(stored, cam, "c")
        assertTrue(err != null)
        assertTrue(cam.swipes < Stepper.MAX_STEPS)
    }
}
