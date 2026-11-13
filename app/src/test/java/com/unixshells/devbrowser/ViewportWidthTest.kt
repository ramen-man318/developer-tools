package com.unixshells.devbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewportWidthTest {
    @Test fun acceptsBoundsAndTypicalWidths() {
        assertEquals(1, ViewportWidth.parse("1"))
        assertEquals(375, ViewportWidth.parse("375"))
        assertEquals(10_000_000, ViewportWidth.parse("10000000"))
    }

    @Test fun rejectsInvalidOrOutOfRangeValues() {
        listOf("", "0", "10000001", "-1", "+1", "1.5", " 375", "375 ", "abc", "999999999999999999999").forEach {
            assertNull(it, ViewportWidth.parse(it))
        }
    }
}
