package com.mtgscanbuild

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseConfigurationTest {
    @Test
    fun publicAppInitializesTheConfiguredFirebaseProject() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.mtgscanbuild", context.packageName)
        val firebase = FirebaseApp.getInstance()
        assertEquals("mtg-scan-build-7b54f", firebase.options.projectId)
        assertNotNull(firebase.options.applicationId)
        assertNotNull(firebase.options.apiKey)
    }
}
