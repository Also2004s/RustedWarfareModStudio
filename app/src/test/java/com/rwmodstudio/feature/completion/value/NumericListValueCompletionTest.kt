package com.rwmodstudio.feature.completion.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 数值列表（ints/point/point3d）定长逗号提示的纯函数单测：
 * 位数上限映射与顶层段计数（上限判定 = 已填段数 < 上限）。
 */
class NumericListValueCompletionTest {

    // ===== numericListMaxValues：type → 定长上限 =====

    @Test
    fun maxValuesPerType() {
        assertEquals(4, numericListMaxValues("ints"), "建筑碰撞范围等为四个数值(左,上,右,下)")
        assertEquals(2, numericListMaxValues("point"), "point 为 x,y")
        assertEquals(3, numericListMaxValues("point3d"), "point3d 为 x,y,z")
    }

    @Test
    fun maxValuesNormalizesType() {
        assertEquals(4, numericListMaxValues(" ints "), "去空格并忽略大小写")
        assertEquals(3, numericListMaxValues("Point3D"))
        assertNull(numericListMaxValues("float"), "普通数值类型不参与")
        assertNull(numericListMaxValues("color"))
        assertNull(numericListMaxValues("resources"))
        assertNull(numericListMaxValues(""))
    }

    // ===== countTopLevelValues：顶层非空段计数 =====

    @Test
    fun countBasicSegments() {
        assertEquals(0, countTopLevelValues(""))
        assertEquals(0, countTopLevelValues("   "))
        assertEquals(1, countTopLevelValues("0"))
        assertEquals(2, countTopLevelValues("0,0"))
        assertEquals(2, countTopLevelValues("10, 20"), "逗号后的空格不计入段")
        assertEquals(3, countTopLevelValues("0, 0, 1"))
        assertEquals(4, countTopLevelValues("0,0,1,1"))
        assertEquals(4, countTopLevelValues("-1,-1,1,3"), "负数照常计数")
    }

    @Test
    fun countIgnoresTrailingSeparatorAndSpace() {
        assertEquals(4, countTopLevelValues("0,0,1,1 "), "尾部空格不影响")
        assertEquals(2, countTopLevelValues("0, 0, "), "尾随逗号（未填下一段）不计入")
        assertEquals(2, countTopLevelValues("0,0,"), "尾随逗号不计入")
    }

    @Test
    fun countRespectsNesting() {
        // 括号内的逗号不是顶层分隔符（数值列表不会出现，但计数须稳健）
        assertEquals(2, countTopLevelValues("fn(0,0), 1"))
        assertEquals(1, countTopLevelValues("{x: 0, y: 1}"))
    }

    // ===== 上限判定组合：已填段数 < 上限 才提示逗号 =====

    @Test
    fun commaOfferedOnlyBelowLimit() {
        val max = numericListMaxValues("ints")!!
        assertEquals(true, countTopLevelValues("0,0,1") < max, "3/4 → 提示")
        assertEquals(false, countTopLevelValues("0,0,1,1") < max, "4/4 → 不提示")
        val p = numericListMaxValues("point")!!
        assertEquals(true, countTopLevelValues("10") < p, "1/2 → 提示")
        assertEquals(false, countTopLevelValues("10, 20") < p, "2/2 → 不提示")
    }
}