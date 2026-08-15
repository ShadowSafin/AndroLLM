package io.androllm.core.runtime

/**
 * One self-contained capability of the app — local GGUF inference, cloud
 * chat, image generation, voice assistant, UI automation, tool calling, MCP…
 *
 * Every runtime registers itself into the central [RuntimeRegistry] through
 * its own module's Hilt `@Binds @IntoSet` binding. Adding a new runtime means
 * implementing this interface and adding one binding — nothing in the
 * registry, other runtimes, or existing code needs to change.
 *
 * Independence contract: runtimes own their own lifecycle and internals. The
 * registry only ever reads [status]; it never starts, stops or configures a
 * runtime, so a failure in one runtime can never disable another (e.g. the
 * image runtime crashing does not touch chat; a cloud provider outage does
 * not touch the GGUF engine).
 */
interface Runtime {

    /** Stable machine id, e.g. `"gguf"`, `"cloud"`, `"image"`, `"voice"`. */
    val id: String

    /** Human name shown in the registry view, e.g. "Local GGUF (llama.cpp)". */
    val displayName: String

    val category: RuntimeCategory

    val description: String

    /**
     * Cheap, side-effect-free availability snapshot. Must never throw: wrap
     * anything risky in `runCatching` so one broken runtime cannot take down
     * the whole registry view.
     */
    suspend fun status(): RuntimeStatus
}
