package com.kira3.ametrine.emucore.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * JSON form of [EmulatorSettings] and of per-game overrides.
 *
 * Reading never fails on a stored file: unknown keys are dropped, and a value of the wrong
 * type, a choice value that is not one of [choices] (or a missing value) falls back to the
 * default, so a renamed or retyped field costs only that one value.
 */
object SettingsJson {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val defaults: JsonObject by lazy { encode(EmulatorSettings()) }

    /**
     * The values each choice setting can take. A stored value outside them, such as one an
     * older version of the app wrote for a setting of the same name, reads as the default.
     */
    private val choices: Map<String, Set<String>> = mapOf(
        "console.region" to setOf("auto", "jp", "na", "eu"),
        "console.cart" to setOf("auto", "none", "backup", "extram1", "extram4"),
        "console.padType" to setOf("standard", "3d"),
        "console.language" to setOf("english", "german", "french", "spanish", "italian", "japanese"),
        "display.aspectRatio" to setOf("4:3", "16:9", "Stretch"),
        "display.deinterlacer" to setOf("weave", "bob", "bob_offset", "blend"),
        "system.backupRam" to setOf("PerGame", "Shared"),
    )

    fun encode(settings: EmulatorSettings): JsonObject = json.encodeToJsonElement(settings).jsonObject

    /** [stored] laid over the defaults. */
    fun decode(stored: JsonObject): EmulatorSettings = overlay(defaults, stored)

    fun parse(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    fun format(obj: JsonObject): String = json.encodeToString(JsonObject.serializer(), obj)

    /** [global] with a game's [overrides] applied: the settings that game runs with. */
    fun merge(global: EmulatorSettings, overrides: SettingsOverrides): EmulatorSettings =
        overlay(encode(global), overrides.json)

    /** Dotted paths ("graphics.fxaa") of every value that differs between [a] and [b]. */
    fun changedPaths(a: EmulatorSettings, b: EmulatorSettings): Set<String> = buildSet {
        diff(encode(a), encode(b), "", this)
    }

    /** [into] with the values at [paths] taken from [from]. */
    fun copyPaths(into: EmulatorSettings, from: EmulatorSettings, paths: Set<String>): EmulatorSettings {
        val source = encode(from)
        val picked = paths.fold(JsonObject(emptyMap())) { acc, path -> acc.withValue(path.split('.'), source.valueAt(path)) }
        return overlay(encode(into), picked)
    }

    /** [settings] with the value at [path] replaced. A value of the wrong type is ignored. */
    internal fun withValue(settings: EmulatorSettings, path: String, value: JsonPrimitive): EmulatorSettings =
        overlay(encode(settings), JsonObject(emptyMap()).withValue(path.split('.'), value))

    private fun overlay(base: JsonObject, top: JsonObject): EmulatorSettings =
        json.decodeFromJsonElement(overlayJson(base, top, ""))

    private fun overlayJson(base: JsonObject, top: JsonObject, prefix: String): JsonObject =
        JsonObject(base.mapValues { (key, value) ->
            val over = top[key] ?: return@mapValues value
            when {
                value is JsonObject && over is JsonObject -> overlayJson(value, over, "$prefix$key.")
                value is JsonPrimitive && over is JsonPrimitive && sameKind(value, over) &&
                    choices[prefix + key]?.contains(over.content) != false -> over
                else -> value
            }
        })

    private fun sameKind(a: JsonPrimitive, b: JsonPrimitive): Boolean = when {
        a.isString -> b.isString
        a.booleanOrNull != null -> !b.isString && b.booleanOrNull != null
        b.isString || b.booleanOrNull != null -> false
        // An Int field cannot take 1.5; a Float field can take 2.
        a.content.toLongOrNull() != null -> b.content.toLongOrNull() != null
        else -> b.content.toDoubleOrNull() != null
    }

    private fun diff(a: JsonObject, b: JsonObject, prefix: String, out: MutableSet<String>) {
        for ((key, value) in a) {
            val other = b[key]
            val path = prefix + key
            if (value is JsonObject && other is JsonObject) diff(value, other, "$path.", out)
            else if (value != other) out += path
        }
    }
}

/**
 * The settings a game sets for itself, as a sparse copy of the [EmulatorSettings] JSON
 * holding only the overridden values. Everything absent follows the global settings.
 */
class SettingsOverrides(val json: JsonObject = JsonObject(emptyMap())) {
    /** Dotted paths ("graphics.fxaa") of the overridden values. */
    val paths: Set<String> by lazy { buildSet { collect(json, "", this) } }

    fun isEmpty(): Boolean = paths.isEmpty()

    operator fun contains(path: String): Boolean = path in paths

    /**
     * Records the values that differ between [before] and [after] (two effective settings for
     * this game, before and after an edit) as overrides.
     */
    fun record(before: EmulatorSettings, after: EmulatorSettings): SettingsOverrides {
        val changed = SettingsJson.changedPaths(before, after)
        if (changed.isEmpty()) return this
        val source = SettingsJson.encode(after)
        return SettingsOverrides(changed.fold(json) { acc, path -> acc.withValue(path.split('.'), source.valueAt(path)) })
    }

    /** Drops the overrides at [paths], so those values follow the global settings again. */
    fun without(paths: Collection<String>): SettingsOverrides =
        SettingsOverrides(paths.fold(json) { acc, path -> acc.withoutValue(path.split('.')) })

    override fun equals(other: Any?): Boolean = other is SettingsOverrides && other.json == json
    override fun hashCode(): Int = json.hashCode()
    override fun toString(): String = "SettingsOverrides($paths)"

    private companion object {
        fun collect(obj: JsonObject, prefix: String, out: MutableSet<String>) {
            for ((key, value) in obj) {
                if (value is JsonObject) collect(value, "$prefix$key.", out) else out += prefix + key
            }
        }
    }
}

internal fun JsonObject.valueAt(path: String): JsonElement =
    path.split('.').fold(this as JsonElement) { element, key -> element.jsonObject.getValue(key) }

private fun JsonObject.withValue(keys: List<String>, value: JsonElement): JsonObject {
    val key = keys.first()
    val next = if (keys.size == 1) value
    else (this[key] as? JsonObject ?: JsonObject(emptyMap())).withValue(keys.drop(1), value)
    return JsonObject(this + (key to next))
}

private fun JsonObject.withoutValue(keys: List<String>): JsonObject {
    val key = keys.first()
    if (keys.size == 1) return JsonObject(this - key)
    val child = (this[key] as? JsonObject ?: return this).withoutValue(keys.drop(1))
    return JsonObject(if (child.isEmpty()) this - key else this + (key to child))
}
