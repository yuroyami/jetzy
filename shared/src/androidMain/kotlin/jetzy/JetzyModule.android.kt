package jetzy

import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

actual val platformModule =  module {
    includes(commonModule)

    // Android-specific dependencies
    single<Context> { androidContext() }

//    // Break the circular dependency by getting activity from context
//    single<P2pHandler> {
//        P2pAndroidHandler(
//            activity = get<Context>() as ComponentActivity,
//            viewmodel = get<JetzyViewmodel>(),
//        )
//    }
}