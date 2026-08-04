package io.androllm.core.common

/**
 * Constants used throughout the application.
 */
object AppConstants {
    const val APP_NAME = "AndroLLM"
    const val PACKAGE_NAME = "io.androllm.app"
    const val DATABASE_NAME = "androllm.db"
    const val DATASTORE_NAME = "preferences.pb"

    object Database {
        const val VERSION = 3
        const val CONVERSATION_TABLE = "conversations"
        const val MESSAGE_TABLE = "messages"
        const val SETTINGS_TABLE = "settings"
        const val MODEL_TABLE = "models"
    }

    object Preferences {
        const val THEME_KEY = "theme"
        const val LANGUAGE_KEY = "language"
        const val DEVELOPER_MODE_KEY = "developer_mode"
        const val STORAGE_PATH_KEY = "storage_path"
        const val FIRST_LAUNCH_KEY = "first_launch"
        const val MODEL_PATH_KEY = "model_path"
    }

    object Navigation {
        const val SPLASH_ROUTE = "splash"
        const val AUTH_ROUTE = "auth"
        const val HOME_ROUTE = "home"
        const val CHAT_ROUTE = "chat"
        const val CHAT_DETAIL_ROUTE = "chat/{conversationId}"
        const val MODELS_ROUTE = "models"
        const val SETTINGS_ROUTE = "settings"
        const val MODEL_DETAIL_ROUTE = "models/{modelId}"
    }

    object Intents {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MESSAGE_ID = "message_id"
    }

    object Permissions {
        const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
        const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
        const val MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
        const val CAMERA = "android.permission.CAMERA"
    }

    object Animation {
        const val SPLASH_DURATION = 2000L
        const val FADE_DURATION = 300
        const val SLIDE_DURATION = 300
        const val CARD_ELEVATION = 8
        const val BUTTON_CORNER_RADIUS = 16
        const val CARD_CORNER_RADIUS = 20
    }

    object Network {
        const val CONNECT_TIMEOUT = 30_000L
        const val READ_TIMEOUT = 60_000L
        const val WRITE_TIMEOUT = 60_000L
        const val MAX_RETRIES = 3
    }

    object Model {
        const val MIN_RAM_GB = 4
        const val RECOMMENDED_RAM_GB = 8
        const val DEFAULT_CONTEXT_LENGTH = 4096
        const val MAX_CONTEXT_LENGTH = 32768
    }
}
