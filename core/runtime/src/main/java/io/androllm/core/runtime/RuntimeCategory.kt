package io.androllm.core.runtime

/**
 * Coarse bucket for every registered runtime — used to group the registry
 * view and to keep the [RuntimeRegistry] addressable by family.
 */
enum class RuntimeCategory(val displayName: String) {
    LLM("Local LLM"),
    CLOUD("Cloud"),
    IMAGE("Image Generation"),
    VOICE("Voice Assistant"),
    AUTOMATION("Automation"),
    TOOLS("Tools"),
    MCP("MCP")
}
