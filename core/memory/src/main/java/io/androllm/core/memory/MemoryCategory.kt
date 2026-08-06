package io.androllm.core.memory

/**
 * The taxonomy of long-term memories the memory system can store.
 * Categories are stored as stable enum names in the database so renames
 * in the display label never break existing rows.
 */
enum class MemoryCategory(val label: String) {
    IDENTITY("Identity"),
    PREFERENCES("Preferences"),
    PROJECTS("Projects"),
    GOALS("Goals"),
    SKILLS("Skills"),
    PROGRAMMING_LANGUAGES("Programming Languages"),
    FRAMEWORKS("Frameworks"),
    DEVICES("Devices"),
    PINNED_FACTS("Pinned Facts"),
    DEVELOPER_NOTES("Developer Notes"),
    CUSTOM("Custom");

    companion object {
        /**
         * Parses any casing/format of a category name coming from LLM JSON
         * (e.g. "programming_languages", "Programming Languages", "PROGRAMMING_LANGUAGES")
         * and falls back to [CUSTOM] for unknown values.
         */
        fun fromName(raw: String?): MemoryCategory {
            if (raw.isNullOrBlank()) return CUSTOM
            val normalized = raw.trim().uppercase().replace(' ', '_').replace('-', '_')
            return entries.firstOrNull { it.name == normalized } ?: CUSTOM
        }
    }
}
