package win.fantest.familytouch

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Only touches the isolated QA package and restores its original selection. */
class ConnectionModeDeviceTest {
    @Test fun remembersExternalAndRejectsModeChangesDuringCalls() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.Main) {
            FamilyStore.init(context); Tailnet.init(context)
            val prefs = context.getSharedPreferences("vpn", 0)
            val previous = prefs.getString("mode", null)
            val previousCall = FamilyStore.activeCall
            try {
                FamilyNetworkMode.change(context, ConnectionMode.EXTERNAL)
                assertEquals(ConnectionMode.EXTERNAL, FamilyNetworkMode.selected(context))
                resumeFamilyNetwork(context)
                assertFalse("External mode must not start the embedded service", EmbeddedTailnet.serviceActive)
                FamilyStore.activeCall = CallInvite("synthetic-peer", false)
                assertTrue(runCatching { FamilyNetworkMode.change(context, ConnectionMode.BUILT_IN) }.isFailure)
                assertEquals(ConnectionMode.EXTERNAL, FamilyNetworkMode.selected(context))
                FamilyStore.activeCall = null
                FamilyNetworkMode.change(context, ConnectionMode.BUILT_IN)
                assertEquals(ConnectionMode.BUILT_IN, FamilyNetworkMode.selected(context))
            } finally {
                FamilyStore.activeCall = previousCall
                prefs.edit().apply { if (previous == null) remove("mode") else putString("mode", previous) }.commit()
            }
        }
    }
}
