package io.androllm.core.network.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Registry holding active repository providers.
 * Enables seamless switching or querying across multiple online model repositories.
 */
@Singleton
class RepositoryRegistry @Inject constructor(
    val huggingFaceRepository: HuggingFaceRepository
) {

    private val providersMap = mapOf(
        huggingFaceRepository.providerId to huggingFaceRepository
    )

    private val _selectedProviderId = MutableStateFlow(huggingFaceRepository.providerId)
    val selectedProviderId: StateFlow<String> = _selectedProviderId

    fun getActiveProvider(): ModelRepositoryProvider {
        return providersMap[_selectedProviderId.value] ?: huggingFaceRepository
    }

    fun selectProvider(providerId: String) {
        if (providersMap.containsKey(providerId)) {
            _selectedProviderId.value = providerId
        }
    }

    fun getAvailableProviders(): List<Pair<String, String>> {
        return providersMap.values.map { it.providerId to it.providerName }
    }
}
