package win.fantest.familytouch

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Independent QA-package test: never touches actual family chat PINs. */
class ChatSecurityDeviceTest {
    @Test fun independentChatPinRejectsWrongEntryAndCanBeRemoved() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("win.fantest.familytouch.qa", ctx.packageName)
        val one = "qa-chat-" + UUID.randomUUID()
        val two = "qa-chat-" + UUID.randomUUID()
        assertFalse(ChatSecurity.enabled(ctx, one))
        ChatSecurity.set(ctx, one, "639147")
        try {
            assertTrue(ChatSecurity.enabled(ctx, one))
            assertFalse(ChatSecurity.enabled(ctx, two))
            assertFalse(ChatSecurity.verify(ctx, one, "123456"))
            assertFalse(ChatSecurity.verify(ctx, two, "639147"))
            assertTrue(ChatSecurity.verify(ctx, one, "639147"))
            assertTrue(ChatSecurity.disable(ctx, one, "639147"))
            assertFalse(ChatSecurity.enabled(ctx, one))
        } finally {
            if (ChatSecurity.enabled(ctx, one)) {
                ChatSecurity.disable(ctx, one, "639147")
            }
        }
    }
}
