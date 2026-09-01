package com.rwmodstudio.feature.completion.value

import com.rwmodstudio.core.ProjectTagScanner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 单位 [core] 建造信息详情格式化单测：
 * 建造速度换算（秒 = 1 ÷ (建造速度 × 60)）与「秒数｜价格」精简详情拼接。
 */
class UnitBuildInfoTest {

    @Test
    fun formatBuildSpeedConvertsRawNumberToSeconds() {
        assertEquals("15.1S", formatBuildSpeed("0.0011"), "0.0011 → 1/(0.0011×60)=15.1515…S 截断为 15.1S")
        assertEquals("9.9S", formatBuildSpeed("0.0016667"), "0.0016667 → 9.9998…S 截断为 9.9S")
        assertEquals("3.3S", formatBuildSpeed("0.005"), "0.005 → 3.3333…S 截断为 3.3S")
    }

    @Test
    fun formatBuildSpeedKeepsNonNumericOrNonPositiveAsIs() {
        assertEquals("3s", formatBuildSpeed("3s"), "已带单位格式（如 3s）原样显示，不换算")
        assertEquals("1.5S", formatBuildSpeed("1.5S"), "已带 S 后缀原样显示")
        assertEquals("0", formatBuildSpeed("0"), "非正数（0）不换算，避免除零")
        assertEquals("-1", formatBuildSpeed("-1"), "负数不换算")
        assertEquals("abc", formatBuildSpeed("abc"), "非数字原样显示")
        assertEquals("", formatBuildSpeed(""), "空串原样显示")
    }

    @Test
    fun unitBuildDetailJoinsSpeedAndPrice() {
        val info = ProjectTagScanner.UnitBuildInfo(price = "500", buildSpeed = "0.0011")
        assertEquals("15.1S｜500", unitBuildDetail(info, "单位名"))
    }

    @Test
    fun unitBuildDetailFiltersZeroPrice() {
        // 价格为 0 → 视为无有效建造信息，只显示单位名
        assertEquals("单位名", unitBuildDetail(ProjectTagScanner.UnitBuildInfo(price = "0", buildSpeed = "0.0011"), "单位名"))
        assertEquals("单位名", unitBuildDetail(ProjectTagScanner.UnitBuildInfo(price = "0"), "单位名"))
        assertEquals("单位名", unitBuildDetail(ProjectTagScanner.UnitBuildInfo(price = "0.0"), "单位名"))
    }

    @Test
    fun unitBuildDetailHandlesMissingFields() {
        // 只有价格
        assertEquals("500", unitBuildDetail(ProjectTagScanner.UnitBuildInfo(price = "500"), "单位名"))
        // 只有建造速度
        assertEquals("15.1S", unitBuildDetail(ProjectTagScanner.UnitBuildInfo(buildSpeed = "0.0011"), "单位名"))
        // 建造速度为带单位格式（不换算，原样）
        assertEquals("3s", unitBuildDetail(ProjectTagScanner.UnitBuildInfo(buildSpeed = "3s"), "单位名"))
        // 全空 → 回退
        assertEquals("单位名", unitBuildDetail(ProjectTagScanner.UnitBuildInfo(), "单位名"))
        // null → 回退
        assertEquals("单位名", unitBuildDetail(null, "单位名"))
    }
}
