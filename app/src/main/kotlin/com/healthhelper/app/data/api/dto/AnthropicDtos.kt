package com.healthhelper.app.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AnthropicMessageRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    @SerialName("output_config") val outputConfig: AnthropicOutputConfig? = null,
    val fallbacks: String? = null,
)

@Serializable
data class AnthropicOutputConfig(
    val effort: String? = null,
    val format: AnthropicOutputFormat? = null,
)

/** Structured output: the final text block is JSON that validates against [schema]. */
@Serializable
data class AnthropicOutputFormat(
    val type: String,
    val schema: JsonObject,
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentItem>,
)

@Serializable
data class AnthropicContentItem(
    val type: String,
    val text: String? = null,
    val source: AnthropicImageSource? = null,
)

@Serializable
data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String,
)

@Serializable
data class AnthropicMessageResponse(
    val id: String = "",
    val content: List<AnthropicResponseContent> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class AnthropicResponseContent(
    val type: String = "",
    val text: String? = null,
)
