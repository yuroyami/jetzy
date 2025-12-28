package jetzy.managers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

abstract class EbfTestClass {
    abstract val someStateFlow: StateFlow<String>
}

final class EbfTestFinalClass: EbfTestClass() {
    override val someStateFlow: StateFlow<String>
        field = MutableStateFlow("")

    fun randomFunction2() {
        someStateFlow.value = "" //Issue 2: val cannot be reassigned
    }
}