package com.healthhelper.app.di

import android.content.Context
import android.content.SharedPreferences
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppModuleTest {

    @Test
    fun `createEncryptedSharedPreferences returns SharedPreferences when creation succeeds`() {
        val mockContext = mockk<Context>()
        val mockPrefs = mockk<SharedPreferences>()

        val result = AppModule.createEncryptedSharedPreferences(mockContext) { mockPrefs }

        assertNotNull(result)
    }

    @Test
    fun `createEncryptedSharedPreferences returns null when creation throws`() {
        val mockContext = mockk<Context>()

        val result = AppModule.createEncryptedSharedPreferences(mockContext) {
            throw RuntimeException("Keystore unavailable")
        }

        assertNull(result)
    }

    @Test
    fun `createEncryptedSharedPreferences does NOT fall back to plaintext on failure`() {
        val mockContext = mockk<Context>()
        var fallbackCalled = false
        // The creator throws — if result is non-null, that means a fallback was used
        val result = AppModule.createEncryptedSharedPreferences(mockContext) {
            throw java.security.GeneralSecurityException("Keystore error")
        }

        // Must be null, not a fallback SharedPreferences
        assertNull(result)
    }
}
