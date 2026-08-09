package io.androllm.core.accessibility.tree

/**
 * Semantic classification of a UI element, used by the screen analyzer and
 * the planners. Everything maps onto common Android view classes AND Compose
 * semantics, so RecyclerViews and Compose hierarchies are handled by the same
 * classifier.
 */
enum class UiElementType(val displayName: String) {
    UNKNOWN("Unknown"),
    TEXT("Text"),
    BUTTON("Button"),
    TEXT_FIELD("Text field"),
    CHECKBOX("Checkbox"),
    RADIO_BUTTON("Radio button"),
    SWITCH("Switch"),
    LIST("List"),
    LIST_ITEM("List item"),
    CARD("Card"),
    IMAGE("Image"),
    TAB("Tab"),
    TAB_BAR("Tab bar"),
    MENU("Menu"),
    DIALOG("Dialog"),
    BOTTOM_SHEET("Bottom sheet"),
    NAVIGATION_BAR("Navigation bar"),
    SCROLLABLE_AREA("Scrollable area"),
    PROGRESS("Progress"),
    WEBVIEW("Web view"),
    COMPOSE_VIEW("Compose view")
}
