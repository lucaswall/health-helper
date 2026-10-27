package com.healthhelper.app.di

import android.content.Context
import android.content.SharedPreferences
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

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
        // The creator throws — if result is non-null, that means a fallback was used
        val result = AppModule.createEncryptedSharedPreferences(
            context = mockContext,
            deletePrefs = {},
            deleteMasterKey = {},
        ) {
            throw java.security.GeneralSecurityException("Keystore error")
        }

        // Must be null, not a fallback SharedPreferences
        assertNull(result)
    }

    @Test
    fun `createEncryptedSharedPreferences deletes prefs file and retries on keyset failure`() {
        val mockContext = mockk<Context>()
        val mockPrefs = mockk<SharedPreferences>()
        var attempts = 0
        var prefsDeleted = 0
        var masterKeyDeleted = 0

        val result = AppModule.createEncryptedSharedPreferences(
            context = mockContext,
            deletePrefs = { prefsDeleted++ },
            deleteMasterKey = { masterKeyDeleted++ },
        ) {
            attempts++
            if (attempts == 1) throw javax.crypto.AEADBadTagException("keyset mismatch")
            mockPrefs
        }

        assertSame(mockPrefs, result)
        assertEquals(2, attempts)
        assertEquals(1, prefsDeleted)
        assertEquals(0, masterKeyDeleted, "master key must only be deleted if the first retry fails")
    }

    @Test
    fun `createEncryptedSharedPreferences deletes master key when prefs-only retry fails`() {
        val mockContext = mockk<Context>()
        val mockPrefs = mockk<SharedPreferences>()
        var attempts = 0
        var masterKeyDeleted = 0

        val result = AppModule.createEncryptedSharedPreferences(
            context = mockContext,
            deletePrefs = {},
            deleteMasterKey = { masterKeyDeleted++ },
        ) {
            attempts++
            if (attempts <= 2) throw java.security.KeyStoreException("bad key")
            mockPrefs
        }

        assertSame(mockPrefs, result)
        assertEquals(3, attempts)
        assertEquals(1, masterKeyDeleted)
    }

    @Test
    fun `createEncryptedSharedPreferences returns null when recovery also fails`() {
        val mockContext = mockk<Context>()
        var attempts = 0

        val result = AppModule.createEncryptedSharedPreferences(
            context = mockContext,
            deletePrefs = {},
            deleteMasterKey = {},
        ) {
            attempts++
            throw java.security.GeneralSecurityException("still broken")
        }

        assertNull(result)
        assertEquals(3, attempts)
    }

    @Test
    fun `createEncryptedSharedPreferences does not delete anything on non-crypto failure`() {
        val mockContext = mockk<Context>()
        var deleted = false

        val result = AppModule.createEncryptedSharedPreferences(
            context = mockContext,
            deletePrefs = { deleted = true },
            deleteMasterKey = { deleted = true },
        ) {
            throw RuntimeException("transient")
        }

        assertNull(result)
        assertFalse(deleted)
    }
}
