package jetzy

import org.koin.dsl.module

actual val platformModule = module {
    includes(commonModule)
    // No platform-specific singletons on the desktop side — the P2pPlatformCallback
    // is wired at the desktopApp entry point.
}
