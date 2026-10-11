package com.bhavya.music.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton navigation coordinator allowing external components (notifications,
 * system intents, shortcuts) to trigger navigation to app destinations.
 */
@Singleton
class AppRouteNavigator @Inject constructor() {
    private val _pendingRoute = MutableStateFlow<String?>(null)
    val pendingRoute: StateFlow<String?> = _pendingRoute.asStateFlow()
    
    private val _pendingExternalLink = MutableStateFlow<String?>(null)
    val pendingExternalLink: StateFlow<String?> = _pendingExternalLink.asStateFlow()

    fun navigateTo(route: String) {
        if (route.isNotBlank()) {
            _pendingRoute.value = route
        }
    }
    
    fun navigateToExternalImport(link: String) {
        if (link.isNotBlank()) {
            _pendingExternalLink.value = link
            _pendingRoute.value = Screen.ExternalPlaylistImport.route
        }
    }

    fun consumeRoute(): String? {
        val route = _pendingRoute.value
        _pendingRoute.value = null
        return route
    }
    
    fun consumeExternalLink(): String? {
        val link = _pendingExternalLink.value
        _pendingExternalLink.value = null
        return link
    }
}
