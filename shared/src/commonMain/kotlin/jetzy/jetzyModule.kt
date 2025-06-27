package jetzy

import jetzy.p2p.P2pHandler
import jetzy.viewmodel.JetzyViewmodel
import org.koin.core.module.Module
import org.koin.dsl.module

val commonModule = module {
    //viewModelOf(::JetzyViewmodel) //This will NOT provide the same instance to both Activity and AdamScreen (root composable)
    single { JetzyViewmodel(inject<P2pHandler>()) } //We use one viewmodel for everything
}

expect val platformModule: Module