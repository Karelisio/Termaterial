package io.termaterial.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysStateTest {

    @Test
    fun `a tapped modifier applies to the next key only`() {
        val state = ExtraKeysState()
        state.tapControl()
        assertEquals(ModifierState.Once, state.control)
        assertTrue(state.consumeControl())
        assertFalse(state.consumeControl())
        assertEquals(ModifierState.Off, state.control)
    }

    @Test
    fun `a long-pressed modifier stays on until released`() {
        val state = ExtraKeysState()
        state.lockAlt()
        assertTrue(state.consumeAlt())
        assertTrue(state.consumeAlt())
        state.lockAlt()
        assertFalse(state.consumeAlt())
    }

    @Test
    fun `tapping an armed or locked modifier releases it`() {
        val state = ExtraKeysState()
        state.tapControl()
        state.tapControl()
        assertEquals(ModifierState.Off, state.control)
        state.lockControl()
        state.tapControl()
        assertEquals(ModifierState.Off, state.control)
    }

    @Test
    fun `control and alt are independent`() {
        val state = ExtraKeysState()
        state.tapControl()
        assertFalse(state.consumeAlt())
        assertTrue(state.consumeControl())
    }
}
