package win.fantest.familytouch

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Check built-in-only selection and call guard without starting or replacing a VPN. */
class ConnectionModeDeviceTest {
    @Test fun builtInOnlySelectionAndRejectsChangesDuringCalls() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("win.fantest.familytouch.qa", context.packageName)
        withContext(Dispatchers.Main) {
            FamilyStore.init(context)
            Tailnet.init(context)
            val preferences = context.getSharedPreferences("vpn", 0)
            val previous = preferences.getString("mode", null)
            val previousCall = FamilyStore.activeCall
            try {
                assertEquals(ConnectionMode.BUILT_IN, FamilyNetworkMode.selected(context))
                assertTrue("External selection is not supported in this embedded build",
                    runCatching { FamilyNetworkMode.change(context, ConnectionMode.EXTERNAL) }.isFailure)
                FamilyStore.activeCall = CallInvite("qa-synthetic-peer", false)
                assertTrue("Connection mode must not be changed during an active call",
                    runCatching { FamilyNetworkMode.change(context, ConnectionMode.BUILT_IN) }.isFailure)
                FamilyStore.activeCall = null
                FamilyNetworkMode.change(context, ConnectionMode.BUILT_IN)
                assertEquals(ConnectionMode.BUILT_IN, FamilyNetworkMode.selected(context))
            } finally {
                FamilyStore.activeCall = previousCall
                preferences.edit().apply {
                    if (previous == null) remove("mode") else putString("mode", previous)
                }.commit()
            }
        }
    }
}
