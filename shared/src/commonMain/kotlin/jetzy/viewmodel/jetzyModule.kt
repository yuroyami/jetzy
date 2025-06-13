package jetzy.viewmodel

import org.koin.dsl.module

val jetzyModule = module {

    //viewModelOf(::JetzyViewmodel) //This will NOT provide the same instance to both Activity and AdamScreen (root composable)
    single { JetzyViewmodel() } //We use one viewmodel for everything

}