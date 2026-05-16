import SwiftUI
import shared

@main
struct iOSApp: App {
    init() {
        UIApplication.shared.isIdleTimerDisabled = true //Keep screen on

        // Wire the Wi-Fi Aware bridge into the shared module on iOS 26+. The Kotlin
        // platform callback reads `MainKt.wifiAwareBridge` when picking a manager
        // for Android peers; null means "fall back to existing transports".
        if #available(iOS 26.0, *) {
            MainKt.wifiAwareBridge = WifiAwareBridgeImpl()
        }
    }

    var body: some Scene {
        WindowGroup {
            MainScreen().ignoresSafeArea(.all)
        }
    }
}


struct MainScreen: UIViewControllerRepresentable {

    func makeUIViewController(context: Context) -> UIViewController {
        MainKt.MainViewController()
    }
    
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
