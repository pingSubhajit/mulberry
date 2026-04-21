package com.subhajit.mulberry.app.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.mulberry.bootstrap.BootstrapRepository
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.navigation.AppRoute
import com.subhajit.mulberry.navigation.BootstrapRouteResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BootstrapUiState(
    val destination: AppRoute? = null
)

@HiltViewModel
class BootstrapViewModel @Inject constructor(
    repository: SessionBootstrapRepository,
    bootstrapRepository: BootstrapRepository,
    private val routeResolver: BootstrapRouteResolver
) : ViewModel() {
    init {
        viewModelScope.launch {
            if (repository.getCurrentSession() != null) {
                bootstrapRepository.refreshBootstrap()
            }
        }
    }

    val uiState = repository.state
        .map { state -> BootstrapUiState(destination = routeResolver.resolve(state)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BootstrapUiState()
        )
}
