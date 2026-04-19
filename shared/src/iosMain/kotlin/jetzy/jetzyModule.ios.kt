package jetzy

import org.koin.dsl.module

actual val platformModule =  module {
    includes(commonModule)
}