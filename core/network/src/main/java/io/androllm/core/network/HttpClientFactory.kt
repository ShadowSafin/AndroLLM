package io.androllm.core.network

import io.androllm.core.common.AppConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Factory for creating configured Ktor clients.
 */
object HttpClientFactory {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Creates a new Ktor client with the standard configuration.
     */
    fun createClient(): HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = AppConstants.Network.CONNECT_TIMEOUT
            requestTimeoutMillis = AppConstants.Network.READ_TIMEOUT
            socketTimeoutMillis = AppConstants.Network.WRITE_TIMEOUT
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }
}
