package com.healthhelper.app.presentation.ui

import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PrivacyPolicyContentTest {

    private val allText = PrivacyPolicyContent.sections.joinToString("\n") { "${it.heading}\n${it.body}" }

    @Test
    @DisplayName("privacy policy discloses every data recipient")
    fun disclosesRecipients() {
        assertTrue(allText.contains("Food Scanner"))
        assertTrue(allText.contains("Anthropic"))
        assertTrue(allText.contains("Sentry"))
    }

    @Test
    @DisplayName("privacy policy names each Health Connect data type the app reads or writes")
    fun namesDataTypes() {
        listOf("Blood pressure", "blood glucose", "hydration", "Nutrition").forEach { type ->
            assertTrue(allText.contains(type, ignoreCase = true), "missing data type: $type")
        }
    }

    @Test
    @DisplayName("every section has non-blank heading and body")
    fun sectionsNonBlank() {
        assertTrue(PrivacyPolicyContent.sections.isNotEmpty())
        PrivacyPolicyContent.sections.forEach {
            assertTrue(it.heading.isNotBlank())
            assertTrue(it.body.isNotBlank())
        }
    }
}
