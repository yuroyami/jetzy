import Foundation
import Network
import shared

#if canImport(WiFiAware)
import WiFiAware
#endif

/// Swift implementation of `JZWifiAwareBridge` (declared in shared Kotlin as
/// `WifiAwareBridge`). Lives in the iOS app target because Apple's `WiFiAware`
/// framework is Swift-only — Kotlin/Native's cinterop only reads ObjC headers
/// and the framework ships only Swift modules.
///
/// Architecture (matching the actual iOS 26 API, where `WiFiAware` is mostly
/// an extension on `Network.framework`):
///   • Publisher (advertise): `NWListener` configured by a `WAPublisherListener`
///     accepts incoming `NWConnection`s from paired peers running our service.
///   • Subscriber (discover): `NWBrowser` configured by a `WASubscriberBrowser`
///     yields browse results whose endpoints we dial.
///   • Connection: `NWConnection(to: endpoint, using: parameters)` where
///     parameters are configured for Wi-Fi Aware via `.wifiAware { ... }`.
///   • Bytes are bridged through paired `Stream.getBoundStreams` so the Kotlin
///     side reads/writes through plain `NSInputStream` / `NSOutputStream`.
///
/// **Pairing**: Wi-Fi Aware only surfaces *already-paired* devices. First-time
/// pairing happens out-of-band (system Settings flow). Until devices are paired,
/// the browser yields no results.
///
/// Entitlement: `com.apple.developer.wifi-aware` (provisioned in the App ID at
/// developer.apple.com). Info.plist must include `WiFiAwareServices`.
@available(iOS 26.0, *)
@objc public class WifiAwareBridgeImpl: NSObject, JZWifiAwareBridge {

    private let serviceName = "jetzy"

    private var listener: NWListener?
    private var browser: NWBrowser?
    private var listenerCallback: JZWifiAwareBridgeListener?

    private var activeConnections: [String: NWConnection] = [:]
    private var pairedDevicesById: [String: WAPairedDevice] = [:]
    private var pairedDevicesTask: Task<Void, Never>?

    // MARK: - JZWifiAwareBridge

    public func isAvailable() -> Bool {
        WACapabilities.supportedFeatures.contains(.wifiAware) &&
            WAPublishableService.allServices[serviceName] != nil
    }

    public func startDiscovery(deviceName: String, listener cb: JZWifiAwareBridgeListener) {
        self.listenerCallback = cb

        guard let publishService = WAPublishableService.allServices[serviceName] else {
            cb.onError(message: "Service '\(serviceName)' not declared in WiFiAwareServices.")
            return
        }
        guard let subscribeService = WASubscribableService.allServices[serviceName] else {
            cb.onError(message: "Service '\(serviceName)' not declared in WiFiAwareServices.")
            return
        }

        startListener(for: publishService, callback: cb)
        startBrowser(for: subscribeService, callback: cb)
        startPairedDeviceWatcher(callback: cb)
    }

    public func stopDiscovery() {
        listener?.cancel(); listener = nil
        browser?.cancel(); browser = nil
        pairedDevicesTask?.cancel(); pairedDevicesTask = nil
    }

    public func connectToPeer(peerId: String) {
        guard let cb = listenerCallback else { return }
        guard let device = pairedDevicesById[peerId] else {
            cb.onError(message: "Unknown peer id: \(peerId)")
            return
        }
        guard let subscribeService = WASubscribableService.allServices[serviceName] else {
            cb.onError(message: "Service missing in plist.")
            return
        }

        // Spin up a one-shot browser targeting just this device; first result is the endpoint we dial.
        let provider: WASubscriberBrowser = .wifiAware(
            .connecting(to: .selected([device]), from: subscribeService)
        )
        let parameters = NWParameters().wifiAware { (p: inout WAParameters) in
            p.performanceMode = .bulk
        }
        provider.configureParameters(parameters)

        let oneShot = NWBrowser(for: provider.makeDescriptor(), using: parameters)
        oneShot.browseResultsChangedHandler = { [weak self] (results: Set<NWBrowser.Result>, _: Set<NWBrowser.Result.Change>) in
            guard let self = self, let firstResult = results.first else { return }
            oneShot.cancel()
            self.startConnection(to: firstResult.endpoint, peerId: peerId, parameters: parameters)
        }
        oneShot.start(queue: DispatchQueue.global(qos: .userInitiated))
    }

    public func cleanup() {
        stopDiscovery()
        for (_, c) in activeConnections { c.cancel() }
        activeConnections.removeAll()
        pairedDevicesById.removeAll()
        listenerCallback = nil
    }

    // MARK: - Listener (accept incoming)

    private func startListener(for service: WAPublishableService, callback cb: JZWifiAwareBridgeListener) {
        let parameters = NWParameters().wifiAware { (p: inout WAParameters) in
            p.performanceMode = .bulk
        }
        let provider: WAPublisherListener = .wifiAware(
            .connecting(to: service, from: .allPairedDevices)
        )
        provider.configureParameters(parameters)

        do {
            let nwListener = try NWListener(service: provider.service, using: parameters)
            nwListener.newConnectionHandler = { [weak self] (conn: NWConnection) in
                guard let self = self else { return }
                let peerId = self.peerIdForConnection(conn)
                self.activeConnections[peerId] = conn
                conn.stateUpdateHandler = { [weak self] (state: NWConnection.State) in
                    self?.handleConnectionState(state, conn: conn, peerId: peerId)
                }
                conn.start(queue: DispatchQueue.global(qos: .userInitiated))
            }
            nwListener.start(queue: DispatchQueue.global(qos: .userInitiated))
            self.listener = nwListener
        } catch {
            cb.onError(message: "Listener: \(error.localizedDescription)")
        }
    }

    // MARK: - Browser (discover outgoing)

    private func startBrowser(for service: WASubscribableService, callback cb: JZWifiAwareBridgeListener) {
        let parameters = NWParameters().wifiAware { (p: inout WAParameters) in
            p.performanceMode = .bulk
        }
        let provider: WASubscriberBrowser = .wifiAware(
            .connecting(to: .allPairedDevices, from: service)
        )
        provider.configureParameters(parameters)

        let nwBrowser = NWBrowser(for: provider.makeDescriptor(), using: parameters)
        nwBrowser.browseResultsChangedHandler = { [weak self] (results: Set<NWBrowser.Result>, _: Set<NWBrowser.Result.Change>) in
            guard let self = self else { return }
            for result in results {
                self.consumeBrowseResult(result, callback: cb)
            }
        }
        nwBrowser.stateUpdateHandler = { (state: NWBrowser.State) in
            if case .failed(let err) = state {
                cb.onError(message: "Browser: \(err.localizedDescription)")
            }
        }
        nwBrowser.start(queue: DispatchQueue.global(qos: .userInitiated))
        self.browser = nwBrowser
    }

    private func consumeBrowseResult(_ result: NWBrowser.Result, callback cb: JZWifiAwareBridgeListener) {
        let stableId = String(describing: result.endpoint)
        let displayName = bestNameForResult(result)
        DispatchQueue.main.async { [weak self] in
            self?.listenerCallback?.onPeerFound(peerId: stableId, peerName: displayName)
        }
    }

    private func bestNameForResult(_ result: NWBrowser.Result) -> String {
        if case let .service(name, _, _, _) = result.endpoint, !name.isEmpty {
            return name
        }
        return "Wi-Fi Aware peer"
    }

    // MARK: - Paired devices watcher

    private func startPairedDeviceWatcher(callback cb: JZWifiAwareBridgeListener) {
        pairedDevicesTask?.cancel()
        pairedDevicesTask = Task { [weak self] in
            do {
                for try await snapshot in WAPairedDevice.allDevices {
                    guard let self = self else { return }
                    let existing = Set(self.pairedDevicesById.keys)
                    var seen: Set<String> = []
                    for (id, device) in snapshot {
                        let key = String(id)
                        seen.insert(key)
                        self.pairedDevicesById[key] = device
                    }
                    for gone in existing.subtracting(seen) {
                        self.pairedDevicesById.removeValue(forKey: gone)
                        DispatchQueue.main.async {
                            cb.onPeerLost(peerId: gone)
                        }
                    }
                }
            } catch {
                NSLog("Jetzy/WiFiAware: paired-devices stream ended: \(error)")
            }
        }
    }

    private func peerIdForConnection(_ conn: NWConnection) -> String {
        return String(describing: conn.endpoint)
    }

    // MARK: - Outbound connection

    private func startConnection(to endpoint: NWEndpoint, peerId: String, parameters: NWParameters) {
        let conn = NWConnection(to: endpoint, using: parameters)
        activeConnections[peerId] = conn
        conn.stateUpdateHandler = { [weak self] (state: NWConnection.State) in
            self?.handleConnectionState(state, conn: conn, peerId: peerId)
        }
        conn.start(queue: DispatchQueue.global(qos: .userInitiated))
    }

    // MARK: - Connection lifecycle

    private func handleConnectionState(_ state: NWConnection.State, conn: NWConnection, peerId: String) {
        switch state {
        case .ready:
            NSLog("Jetzy/WiFiAware: connection ready to \(peerId)")
            handleReady(conn: conn, peerId: peerId)
        case .failed(let err):
            NSLog("Jetzy/WiFiAware: failed: \(err)")
            DispatchQueue.main.async { [weak self] in
                self?.listenerCallback?.onError(message: "Connection: \(err.localizedDescription)")
            }
            activeConnections.removeValue(forKey: peerId)
        case .cancelled:
            activeConnections.removeValue(forKey: peerId)
        default:
            break
        }
    }

    /// Once NWConnection is `.ready`, set up bound NSStream pairs and pump bytes
    /// between NWConnection and the streams. Hand Kotlin-facing halves to the listener.
    private func handleReady(conn: NWConnection, peerId: String) {
        var kotlinInputStream: InputStream?
        var nwSideOutputStream: OutputStream?
        Stream.getBoundStreams(withBufferSize: 64 * 1024,
                               inputStream: &kotlinInputStream,
                               outputStream: &nwSideOutputStream)

        var kotlinOutputStream: OutputStream?
        var nwSideInputStream: InputStream?
        Stream.getBoundStreams(withBufferSize: 64 * 1024,
                               inputStream: &nwSideInputStream,
                               outputStream: &kotlinOutputStream)

        guard let ki = kotlinInputStream, let no = nwSideOutputStream,
              let ko = kotlinOutputStream, let ni = nwSideInputStream
        else {
            listenerCallback?.onError(message: "Couldn't bind Wi-Fi Aware streams.")
            return
        }

        no.open(); ni.open()
        pumpInbound(conn: conn, into: no)
        pumpOutbound(from: ni, to: conn)

        DispatchQueue.main.async { [weak self] in
            self?.listenerCallback?.onConnected(peerId: peerId, input: ki, output: ko)
        }
    }

    private func pumpInbound(conn: NWConnection, into outputStream: OutputStream) {
        func nextChunk() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                if let data = data, !data.isEmpty {
                    data.withUnsafeBytes { raw in
                        if let base = raw.baseAddress {
                            let ptr = base.assumingMemoryBound(to: UInt8.self)
                            var written = 0
                            while written < data.count {
                                let n = outputStream.write(ptr.advanced(by: written), maxLength: data.count - written)
                                if n <= 0 { break }
                                written += n
                            }
                        }
                    }
                }
                if let error = error {
                    NSLog("Jetzy/WiFiAware: inbound error \(error)")
                    outputStream.close()
                    return
                }
                if isComplete {
                    outputStream.close()
                    return
                }
                nextChunk()
            }
        }
        nextChunk()
    }

    private func pumpOutbound(from inputStream: InputStream, to conn: NWConnection) {
        DispatchQueue.global(qos: .userInitiated).async {
            let bufSize = 64 * 1024
            let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: bufSize)
            defer { buf.deallocate() }

            while true {
                let n = inputStream.read(buf, maxLength: bufSize)
                if n <= 0 {
                    conn.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .idempotent)
                    return
                }
                let data = Data(bytes: buf, count: n)
                let sem = DispatchSemaphore(value: 0)
                var sendError: NWError?
                conn.send(content: data, completion: .contentProcessed { err in
                    sendError = err
                    sem.signal()
                })
                sem.wait()
                if let err = sendError {
                    NSLog("Jetzy/WiFiAware: outbound error \(err)")
                    return
                }
            }
        }
    }
}
