package com.xyf.mastergrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterListsTest {
    @Test
    fun split() {
        assertEquals("阴天 拍花" to "和光", FilterLists.split("和光 - 阴天 拍花"))
        assertEquals("标准" to null, FilterLists.split("标准"))
    }

    @Test
    fun `custom part is left of standard`() {
        assertEquals(listOf("A - 1", "B - 2"), FilterLists.customPart(listOf("A - 1", "B - 2", "标准", "鲜艳")))
        assertEquals(listOf("A - 1"), FilterLists.customPart(listOf("鲜艳", "A - 1")))
    }

    @Test
    fun `merge keeps user order and appends new ones`() {
        assertEquals(listOf("c", "a", "d"), FilterLists.mergeOrder(listOf("c", "b", "a"), listOf("a", "c", "d")))
    }

    @Test
    fun `sort by style follows system filter order`() {
        val all = listOf("和光 - 1", "人文 - 2", "和光 - 3", "标准", "人文", "和光")
        assertEquals(
            listOf("人文 - 2", "和光 - 1", "和光 - 3"),
            FilterLists.sortByStyle(all, listOf("和光 - 1", "人文 - 2", "和光 - 3")),
        )
    }

    @Test
    fun `orient scan reverses when custom filters are at the end`() {
        val forward = listOf("A - 1", "B - 2", "标准", "鲜艳")
        assertEquals(forward to false, FilterLists.orientScan(forward))
        assertEquals(forward to true, FilterLists.orientScan(forward.reversed()))
    }

    @Test
    fun `learner inserts several new filters in order`() {
        val l = ListLearner(listOf("a", "b", "c"))
        listOf("a", "x", "y", "b", "c").forEach(l::see)
        assertEquals(listOf("a", "x", "y", "b", "c"), l.result())
    }

    @Test
    fun `learner handles backward observation`() {
        val l = ListLearner(listOf("a", "b", "c"))
        listOf("c", "y", "x", "b").forEach(l::see)
        assertEquals(listOf("a", "b", "x", "y", "c"), l.result())
    }

    @Test
    fun `learner ignores gaps between non-adjacent known filters`() {
        // 快速拨动时可能漏读名字：a 和 c 不相邻，x 的位置不确定，不插
        val l = ListLearner(listOf("a", "b", "c"))
        listOf("a", "x", "c").forEach(l::see)
        assertNull(l.result())
    }

    @Test
    fun `learner ignores unknowns at the end of the path`() {
        val l = ListLearner(listOf("a", "b"))
        listOf("a", "b", "x").forEach(l::see)
        assertNull(l.result())
    }
}
