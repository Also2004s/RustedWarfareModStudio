package com.rwmodstudio.feature.completion.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectRefKindTest {

    @Test
    fun refTypesMapToKinds() {
        assertEquals("turret", projectRefKind("turret ref"))
        assertEquals("turret", projectRefKind("Turret Ref"))
        assertEquals("projectile", projectRefKind("projectile ref"))
        assertEquals("effect", projectRefKind("effect ref"))
        assertEquals("effect", projectRefKind("effect ref list"))
        assertEquals("effect", projectRefKind("effects"))
        assertEquals("action", projectRefKind("action refs"))
        assertEquals("action", projectRefKind("actions"))
        assertEquals("animation", projectRefKind("animation ref"))
        assertEquals("decal", projectRefKind("decal refs"))
        assertEquals("attachment", projectRefKind("attachment ref"))
        assertEquals("canbuild", projectRefKind("canBuild"))
        assertEquals("sound", projectRefKind("sound ref"))
        assertEquals("sound", projectRefKind("sound(s)"))
    }

    @Test
    fun unrelatedTypesReturnNull() {
        assertNull(projectRefKind("bool"))
        assertNull(projectRefKind("unit ref"))
        assertNull(projectRefKind("string"))
        assertNull(projectRefKind("logicboolean"))
    }
}