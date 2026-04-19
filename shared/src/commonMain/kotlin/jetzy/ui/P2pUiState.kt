package jetzy.ui

import jetzy.p2p.P2pDiscoveryMode

sealed class P2pUiState {

    data object Idle : P2pUiState()

    data class Discovering(val discoveryMode: P2pDiscoveryMode) : P2pUiState()

    data class Transferring(val isSender: Boolean) : P2pUiState()

}