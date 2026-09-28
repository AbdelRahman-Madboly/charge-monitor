package com.vinex.chargemonitor

import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBusTest {
    @Test
    fun publishNeverClearsTheVisibilityFlagSetByTheUi() {
        LiveBus.setLiveVisible(true)
        LiveBus.publish { LiveState(isCharging = true) }
        assertTrue(LiveBus.state.value.liveVisible)
        assertTrue(LiveBus.state.value.isCharging)
        LiveBus.setLiveVisible(false)
    }
}
