package io.androllm.feature.coding.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Tiny helpers for building the JSON Schemas coding tools advertise. */
internal object Schemas {

    fun string(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
        if (enum != null) {
            put("enum", kotlinx.serialization.json.JsonArray(enum.map { JsonPrimitive(it) }))
        }
    }

    fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
    }

    fun integer(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(description))
    }

    /** Array schema with an item schema. */
    fun array(items: JsonObject, description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", items)
        put("description", JsonPrimitive(description))
    }

    /** Object schema with required fields. */
    fun obj(properties: Map<String, JsonObject>, required: List<String>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { properties.forEach { (k, v) -> put(k, v) } })
        put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", JsonPrimitive(false))
    }
}

/** Extracts a string argument or null. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

/** Extracts a boolean argument with a default. */
internal fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default

/** Extracts an integer argument with a default. */
internal fun JsonObject.int(key: String, default: Int): Int =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: default
