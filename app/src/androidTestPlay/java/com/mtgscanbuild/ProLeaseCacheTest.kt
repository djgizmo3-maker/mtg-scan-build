package com.mtgscanbuild

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.GeneralSecurityException

@RunWith(AndroidJUnit4::class)
class ProLeaseCacheTest {
    @Test
    fun encryptedLeaseIsAccountBootAndExpiryBoundAndDetectsTampering() {
        val parent = InstrumentationRegistry.getInstrumentation().targetContext
        val context = parent.createPackageContext(parent.packageName, Context.CONTEXT_IGNORE_SECURITY)
        val cache = ProLeaseCache(context)
        try {
            cache.clear()
            cache.save("owner", 3, 100, 1000)
            assertEquals(1000L, cache.load("owner", 3, 101))
            assertNull(cache.load("different", 3, 101))
            assertNull(cache.load("owner", 4, 101))
            assertNull(cache.load("owner", 3, 99))
            assertNull(cache.load("owner", 3, 1000))
            val file = File(context.noBackupFilesDir, "pro-lease")
            val bytes = file.readBytes()
            assertFalse(String(bytes, Charsets.UTF_8).contains("owner"))
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertThrows(GeneralSecurityException::class.java) { cache.load("owner", 3, 101) }
            cache.clear()
            assertNull(cache.load("owner", 3, 101))
        } finally {
            cache.clear()
        }
    }
}
