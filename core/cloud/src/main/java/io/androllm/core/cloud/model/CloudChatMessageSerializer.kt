package io.androllm.core.cloud.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire format for [CloudChatMessage]. OpenAI-compatible messages carry
 * `content` as either a plain string or an array of content blocks (vision
 * images, etc.), plus optional `tool_calls`/`tool_call_id`/`name` fields.
 *
 * The serializer emits exactly one `content` form (string, array, or `null`
 * for assistant tool-call messages) and tolerates unknown content-block
 * types when decoding responses.
 */
object CloudChatMessageSerializer : KSerializer<CloudChatMessage> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CloudChatMessage") {
        element<String>("role")
        element<String>("content")
        element<String>("tool_call_id")
        element<String>("name")
    }

    override fun serialize(encoder: Encoder, value: CloudChatMessage) {
        val objectJson = buildJsonObject {
            put("role", JsonPrimitive(value.role))
            val content: JsonElement = when {
                value.contentParts != null -> encodeParts(value.contentParts)
                value.content != null -> JsonPrimitive(value.content)
                else -> JsonNull
            }
            put("content", content)
            value.toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
            value.name?.let { put("name", JsonPrimitive(it)) }
            value.toolCalls?.let {
                put("tool_calls", json.encodeToJsonElement(ListSerializer(CloudToolCall.serializer()), it))
            }
        }
        encoder.encodeSerializableValue(JsonObject.serializer(), objectJson)
    }

    override fun deserialize(decoder: Decoder): CloudChatMessage {
        val obj = decoder.decodeSerializableValue(JsonObject.serializer())
        val contentElement: JsonElement? = obj["content"]
        var content: String? = null
        var parts: List<CloudContentPart>? = null
        when (contentElement) {
            null, is JsonNull -> Unit
            is JsonPrimitive -> content = contentElement.content
            is JsonArray -> parts = decodeParts(contentElement)
            is JsonObject -> content = contentElement.toString()
        }
        return CloudChatMessage(
            role = obj["role"]?.jsonPrimitive?.content ?: "",
            content = content,
            contentParts = parts,
            toolCallId = obj["tool_call_id"]?.jsonPrimitive?.content,
            name = obj["name"]?.jsonPrimitive?.content,
            toolCalls = obj["tool_calls"]?.let {
                runCatching {
                    json.decodeFromJsonElement(ListSerializer(CloudToolCall.serializer()), it)
                }.getOrNull()
            }
        )
    }

    private fun encodeParts(parts: List<CloudContentPart>): JsonArray = JsonArray(
        parts.map { part ->
            when (part) {
                is CloudContentPart.Text -> buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(part.text))
                }
                is CloudContentPart.Image -> buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put(
                        "image_url",
                        buildJsonObject { put("url", JsonPrimitive(part.url)) }
                    )
                }
            }
        }
    )

    /** Decodes content blocks, skipping unknown types so responses stay robust. */
    private fun decodeParts(array: JsonArray): List<CloudContentPart> = array.mapNotNull { element ->
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        when (obj["type"]?.jsonPrimitive?.content) {
            "text" -> obj["text"]?.jsonPrimitive?.content?.let { CloudContentPart.Text(it) }
            "image_url" -> obj["image_url"]
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.content
                ?.let { CloudContentPart.Image(it) }
            else -> null
        }
    }
}
