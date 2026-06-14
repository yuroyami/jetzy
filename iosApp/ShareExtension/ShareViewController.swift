import UIKit
import UniformTypeIdentifiers

/// Jetzy iOS Share Extension.
///
/// Receives items shared into Jetzy from Photos / Files / Safari / any app, copies them into the
/// shared App Group "Inbox" folder, then hands back. The main app drains that folder on launch and
/// on every return to foreground (see `SharedInbox.kt`) and stages the files into the send tray —
/// the iOS counterpart of Android's share-sheet intake.
///
/// Wiring (one-time, in Xcode + your Apple account): see `iosApp/SHARE_EXTENSION_SETUP.md`.
final class ShareViewController: UIViewController {

    /// MUST match `SharedInbox.APP_GROUP` and the App Group id on both targets.
    private let appGroup = "group.com.yuroyami.jetzy"

    override func viewDidLoad() {
        super.viewDidLoad()
        process()
    }

    private func inboxURL() -> URL? {
        guard let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup) else { return nil }
        let inbox = container.appendingPathComponent("Inbox", isDirectory: true)
        try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        return inbox
    }

    private func process() {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem])?
            .flatMap { $0.attachments ?? [] } ?? []
        guard !providers.isEmpty, let inbox = inboxURL() else { return finish() }

        let group = DispatchGroup()
        for provider in providers {
            group.enter()
            // UTType.item matches files, images, videos, PDFs — anything with a file representation.
            provider.loadFileRepresentation(forTypeIdentifier: UTType.item.identifier) { url, _ in
                defer { group.leave() }
                guard let url = url else { return }
                // Unique prefix avoids collisions across multiple shares of same-named files.
                let dest = inbox.appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
                try? FileManager.default.copyItem(at: url, to: dest)
            }
        }
        group.notify(queue: .main) { [weak self] in self?.finish() }
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: nil) { [weak self] _ in
            self?.openHostApp()
        }
    }

    /// Best-effort: bring Jetzy to the foreground so it drains the inbox immediately. Requires the
    /// `jetzy` URL scheme registered on the main app (optional — without it, the file is still
    /// picked up next time the user opens Jetzy).
    private func openHostApp() {
        guard let url = URL(string: "jetzy://shared") else { return }
        var responder: UIResponder? = self
        while let r = responder {
            if let app = r as? UIApplication {
                app.open(url, options: [:], completionHandler: nil)
                return
            }
            responder = r.next
        }
    }
}
