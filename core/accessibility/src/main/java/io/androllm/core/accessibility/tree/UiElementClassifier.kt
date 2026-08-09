package io.androllm.core.accessibility.tree

/**
 * Pure element classifier: turns raw node properties into a semantic
 * [UiElementType] plus a confidence score. Kept free of Android framework
 * types so it is fully unit-testable.
 *
 * Class-name matching covers the common view classes AND Compose semantics
 * ("androidx.compose.ui.platform.ComposeView" with Button semantics become
 * buttons via their clickable flag — Compose exposes semantics as
 * clickable/editable/checked properties rather than view subclasses).
 */
object UiElementClassifier {

    private const val CERTAIN = 1.0f
    private const val STRONG = 0.85f
    private const val GUESS = 0.5f
    private const val WEAK = 0.3f

    fun classify(
        className: String?,
        resourceId: String?,
        text: String?,
        contentDescription: String?,
        editable: Boolean,
        clickable: Boolean,
        checkable: Boolean,
        checked: Boolean,
        scrollable: Boolean,
        childCount: Int
    ): Pair<UiElementType, Float> {
        val cls = className.orEmpty()

        // Editable wins — any editable node is a text field regardless of class.
        if (editable || cls.contains("EditText") || cls.contains("SearchView")) {
            return UiElementType.TEXT_FIELD to CERTAIN
        }

        // Explicit widget classes first (highest confidence).
        when {
            cls.contains("CheckBox") -> return UiElementType.CHECKBOX to CERTAIN
            cls.contains("RadioButton") -> return UiElementType.RADIO_BUTTON to CERTAIN
            cls.contains("Switch") -> return UiElementType.SWITCH to CERTAIN
            cls.contains("Button") -> return UiElementType.BUTTON to CERTAIN
            cls.contains("TabLayout") || (cls.contains("Tab") && cls.contains("Widget")) ->
                return UiElementType.TAB to CERTAIN
            cls.contains("ProgressBar") -> return UiElementType.PROGRESS to CERTAIN
            cls.contains("WebView") -> return UiElementType.WEBVIEW to CERTAIN
            cls.contains("Dialog") || cls.contains("PopupWindow") -> return UiElementType.DIALOG to STRONG
            cls.contains("BottomSheet") -> return UiElementType.BOTTOM_SHEET to STRONG
            cls.contains("RecyclerView") || cls.contains("ListView") ||
                cls.contains("GridView") || cls.contains("NestedScrollView") -> {
                return if (scrollable || childCount > 0) {
                    UiElementType.LIST to STRONG
                } else {
                    UiElementType.LIST to GUESS
                }
            }
        }

        // Compose hosts expose semantics through properties, not classes.
        if (cls.contains("androidx.compose")) {
            when {
                checkable && cls.contains("Switch") -> return UiElementType.SWITCH to STRONG
                checkable -> return UiElementType.CHECKBOX to STRONG
                clickable -> return UiElementType.BUTTON to STRONG
                else -> return UiElementType.COMPOSE_VIEW to WEAK
            }
        }

        // Generic behaviour-based classification.
        val hasLabel = !text.isNullOrBlank() || !contentDescription.isNullOrBlank()
        when {
            clickable && hasLabel -> return UiElementType.BUTTON to GUESS
            clickable -> return UiElementType.BUTTON to WEAK
            scrollable -> return UiElementType.SCROLLABLE_AREA to GUESS
            childCount > 3 && (cls.contains("Layout") || cls.contains("Linear")) ->
                return UiElementType.CARD to WEAK
        }

        // Resource id hints.
        val id = resourceId.orEmpty().substringAfterLast('/')
        if (id.contains("button", ignoreCase = true)) return UiElementType.BUTTON to GUESS
        if (id.contains("search", ignoreCase = true)) return UiElementType.TEXT_FIELD to GUESS

        if (hasLabel) return UiElementType.TEXT to STRONG
        return UiElementType.UNKNOWN to WEAK
    }
}
