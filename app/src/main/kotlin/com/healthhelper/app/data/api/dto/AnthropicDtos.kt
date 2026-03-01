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
    val tools: List<AnthropicToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: AnthropicToolChoice? = null,
)

@Serializable
data class AnthropicToolDefinition(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

@Serializable
data class AnthropicToolChoice(
    val type: String,
    val name: String? = null,
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
)

@Serializable
data class AnthropicResponseContent(
    val type: String = "",
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
)
