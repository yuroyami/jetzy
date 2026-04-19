package jetzy.p2p

import kotlinx.cinterop.ExperimentalForeignApi
import platform.MultipeerConnectivity.MCPeerID

@OptIn(ExperimentalForeignApi::class)
class P2pAppleHandler /*: P2pHandler()*/ {

//    private var mcAdvertiser: MCNearbyServiceAdvertiser
//    private var mcSession: MCSession
//    private val mcServiceType = "gmixp2p"
//    private var mcBrowser: MCNearbyServiceBrowser
//    private var mcDelegate: MCDelegate = MCDelegate()
//
//    inner class MCDelegate : NSObject(),
//        MCNearbyServiceBrowserDelegateProtocol,
//        MCNearbyServiceAdvertiserDelegateProtocol,
//        MCSessionDelegateProtocol
//    {
//        /** Called when the browser loses a peer (a peer disappears) */
//        override fun browser(browser: MCNearbyServiceBrowser, lostPeer: MCPeerID) {
//            viewmodel.p2pPeers.removeAll { it.peerId == lostPeer }
//        }
//
//        /** Called when a new peer is discovered */
//        override fun browser(browser: MCNearbyServiceBrowser, foundPeer: MCPeerID, withDiscoveryInfo: Map<Any?, *>?) {
//            viewmodel.p2pPeers.clear()
//            val newPeer = PeerDevice(peerId = foundPeer, peerName = foundPeer.displayName)
//            val peerIdsList: List<MCPeerID> = viewmodel.p2pPeers.map { it.peerId }
//            if (!peerIdsList.contains(foundPeer)) {
//                viewmodel.p2pPeers.add(newPeer)
//            }
//            //viewmodel.p2pPeers.add(newPeer)
//        }
//
//        /** Called when receiving an invitation from a peer */
//        override fun advertiser(
//            advertiser: MCNearbyServiceAdvertiser,
//            didReceiveInvitationFromPeer: MCPeerID,
//            withContext: NSData?,
//            invitationHandler: (Boolean, MCSession?) -> Unit
//        ) {
//            //if (selectedPeer == null) {
//            selectedPeer =
//                PeerDevice(peerId = didReceiveInvitationFromPeer, peerName = didReceiveInvitationFromPeer.displayName)
//            invitationHandler(true, mcSession) //This basically accepts the invitation right away
//            carryOnP2P()
//            //}
//        }
//
//        /** Called when a sender starts sending a stream */
//        override fun session(
//            session: MCSession,
//            didReceiveStream: NSInputStream,
//            withName: String,
//            fromPeer: MCPeerID
//        ) {
//            if (p2pInput == null) p2pInput = didReceiveStream.asSource().buffered()
//        }
//
//        override fun session(
//            session: MCSession,
//            didFinishReceivingResourceWithName: String,
//            fromPeer: MCPeerID,
//            atURL: NSURL?,
//            withError: NSError?
//        ) {
//        }
//
//        override fun session(
//            session: MCSession,
//            didStartReceivingResourceWithName: String,
//            fromPeer: MCPeerID,
//            withProgress: NSProgress
//        ) {
//        }
//
//        override fun session(session: MCSession, didReceiveData: NSData, fromPeer: MCPeerID) {}
//        override fun session(session: MCSession, peer: MCPeerID, didChangeState: MCSessionState) {}
//
//        override fun browser(browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers: NSError) {
//            val errorMessage = "BROWSER Error ${didNotStartBrowsingForPeers.code}\n" +
//                    "Message: ${didNotStartBrowsingForPeers.localizedDescription}\n" +
//                    "Reason: ${didNotStartBrowsingForPeers.localizedFailureReason ?: "None"}"
//
//            viewmodel.snacky(errorMessage)
//        }
//
//        override fun advertiser(advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer: NSError) {
//            val errorMessage = "ADVERTISER Error ${didNotStartAdvertisingPeer.code}\n" +
//                    "Message: ${didNotStartAdvertisingPeer.localizedDescription}\n" +
//                    "Reason: ${didNotStartAdvertisingPeer.localizedFailureReason ?: "None"}"
//
//            viewmodel.snacky(errorMessage)
//        }
//    }
//
//    //private val peerSelf = MCPeerID(displayName = UIDevice.currentDevice.name)
//    private val peerSelf = MCPeerID(displayName = NSProcessInfo.processInfo.hostName.removeSuffix(".local"))
//
//    override var p2pMode: P2pMode? = P2pMode.IOS_TO_IOS
//
//    override fun startNative() {
//        p2pMode = P2pMode.IOS_TO_IOS
//        selectedPeer = null
//    }
//
//    /** Initializing everything we need about Multipeer Connectivity */
//    init {
//        mcSession = MCSession(peer = peerSelf, securityIdentity = null, encryptionPreference = MCEncryptionNone)
//
//        selectedPeer = null
//
//        mcAdvertiser = MCNearbyServiceAdvertiser(
//            peer = peerSelf,
//            discoveryInfo = null,
//            serviceType = mcServiceType
//        )
//
//        mcBrowser = MCNearbyServiceBrowser(peer = peerSelf, serviceType = mcServiceType)
//
//        mcBrowser.delegate = mcDelegate
//        mcAdvertiser.delegate = mcDelegate
//        mcSession.delegate = mcDelegate
//
//        if (viewmodel.playlist.isNotEmpty()) {
//            mcAdvertiser.startAdvertisingPeer()
//            mcBrowser.startBrowsingForPeers()
//        } else {
//            mcBrowser.startBrowsingForPeers()
//        }
//    }
//
//    fun carryOnP2P() {
//        loggy("Connecting to Peer Name: ${selectedPeer?.peerName ?: crossPeer}")
//        loggy("Connecting to Peer ID: ${selectedPeer?.peerId ?: crossPeer}")
//
//        coroutineP2p.launch {
//            if (p2pMode != P2pMode.CROSS_PLATFORM) {
//                while (selectedPeer == null) {
//                    delay(100)
//                }
//
//                while (mcSession.connectedPeers.isEmpty()) {
//                    loggy("Waiting for MC peer connection")
//                    delay(1000)
//                }
//            }
//
//            viewmodel.transferButtonPositive.value = false
//            viewmodel.transferButtonText.value = "Cancel transfer"
//            viewmodel.transferButtonIcon.value = Icons.Outlined.Cancel
//
//            viewmodel.p2pQRpopup.value = false
//            viewmodel.p2pTransferPopup.value = true //Show transfer window
//            viewmodel.p2pChoosePeerPopup.value = false
//
//            val isReceiving = viewmodel.playlist.isEmpty()
//
//            viewmodel.transferStatusText.value = "Initiating connection..."
//
//            if (isReceiving) {
//                try {
//                    if (p2pMode != P2pMode.CROSS_PLATFORM) {
//                        withTimeout(20000) {
//                            while (p2pInput == null) {
//                                delay(50)
//                            }
//                        }
//                    }
//                    receivePlaylist()
//                } catch (e: Exception) {
//                    loggy(e.stackTraceToString())
//                }
//            } else {
//                if (p2pMode != P2pMode.CROSS_PLATFORM) {
//                    p2pOutput = mcSession.startStreamWithName(
//                        streamName = "Gmix playlist",
//                        toPeer = selectedPeer!!.peerId,
//                        error = null
//                    )?.asSink()?.buffered()
//                } else {
//                    sendPlaylistCross()
//                    return@launch
//                }
//                sendPlaylist()
//            }
//        }
//    }
//
//    override fun promptSavePlaylist() {
//        filepickerTell.invoke()
//    }
//
//    @OptIn(BetaInteropApi::class)
//    override fun songSource(
//        song: GmixSong,
//        doLast: CompletableDeferred<() -> Unit>?
//    ): CompletableDeferred<Triple<RawSource, Long, String>> {
//        val future = CompletableDeferred<Triple<RawSource, Long, String>>()
//        coroutineP2p.launch {
//            if (!song.isCloudItem) {
//                val data = NSUserDefaults.standardUserDefaults.dataForKey(song.uri)
//                if (data != null) {
//                    val urlSong = NSURL.URLByResolvingBookmarkData(
//                        bookmarkData = data,
//                        options = NSURLBookmarkResolutionWithoutUI,
//                        relativeToURL = null,
//                        bookmarkDataIsStale = null,
//                        error = null
//                    )
//
//                    if (urlSong != null) {
//                        val access = urlSong.startAccessingSecurityScopedResource()
//                        val content = NSData.dataWithContentsOfURL(urlSong)
//                        if (content == null) {
//                            future.completeExceptionally(Exception("Song invalid for reading from disk 14"))
//                            return@launch
//                        }
//                        val filesize = content.length.toLong()
//
//                        val filename = NSString.create(string = song.uri.substringAfterLast("/"))
//                            .stringByRemovingPercentEncoding() ?: "Undefined file"
//
//                        println("FINAL NAMEEEEE: $filename")
//
//                        val stream = NSInputStream.inputStreamWithData(content)
//
//                        stream?.asSource()?.let {
//                            future.complete(Triple(it, filesize, filename))
//                        }
//
//                        doLast?.complete {
//                            loggy("DID LAST!!!")
//                            if (access) urlSong.stopAccessingSecurityScopedResource()
//                        }
//                    } else future.completeExceptionally(Exception("Song invalid for reading from disk 15"))
//                } else future.completeExceptionally(Exception("Song invalid for reading from disk 16"))
//            } else future.completeExceptionally(Exception("Song invalid for reading from disk 17"))
//        }
//
//        return future
//    }
//
//    override fun songSink(
//        tempName: String,
//        scope: P2pTempFolder
//    ): CompletableDeferred<Pair<RawSink, P2pReceivedFile?>> {
//        val future = CompletableDeferred<Pair<RawSink, P2pReceivedFile?>>()
//
//        val product = scope.tempUrl.URLByAppendingPathComponent(tempName) ?: return future.also {
//            future.completeExceptionally(Exception("Song invalid for writing to disk 18"))
//        }
//
//        product.startAccessingSecurityScopedResource()
//        val sink = NSOutputStream.outputStreamWithURL(product, false)?.asSink() ?: return future.also {
//            future.completeExceptionally(Exception("Song invalid for writing to disk 19 "))
//        }
//
//        future.complete(Pair(sink, P2pReceivedFile(tempName, product)))
//        return future
//    }
//
//    override fun songRename(
//        oldName: String,
//        scope: P2pTempFolder,
//        tempProduct: P2pReceivedFile,
//        finalName: String
//    ): CompletableDeferred<P2pReceivedFile> {
//        val future = CompletableDeferred<P2pReceivedFile>()
//
//        loggy("OPERATION PHOENIX: Renaming began!")
//        loggy("OPERATION PHOENIX: Renaming from '$oldName' to '$finalName' - Temp URL: ${tempProduct.url} - Temp SCOPE: ${scope.tempUrl}")
//
//        val product = tempProduct.url
//
//        product.startAccessingSecurityScopedResource()
//
//        val fDesti = scope.tempUrl.URLByAppendingPathComponent(finalName)
//
//        loggy("OPERATION PHOENIX: Final product should at: $fDesti")
//        if (fDesti != null) {
//
//            NSFileManager.defaultManager.moveItemAtURL(
//                srcURL = product,
//                toURL = fDesti,
//                null
//            )
//
//            future.complete(
//                P2pReceivedFile(name = finalName, url = fDesti)
//            )
//        }
//
//        return future
//    }
//
//    override fun connectNativePeer(peer: P2pPeer) {
//        selectedPeer = peer
//        mcBrowser.invitePeer(
//            peer.peerId,
//            toSession = p2pDelegate!!.mcSession,
//            withContext = null,
//            timeout = 60.0
//        )
//
//        carryOnP2P()
//    }
//
//    override suspend fun withinTempFolder(doThis: suspend P2pTempFolder.() -> Unit) {
//        try {
//            val manager = NSFileManager.defaultManager
//            (manager.URLsForDirectory(
//                directory = NSCachesDirectory, inDomains = NSUserDomainMask
//            ).first() as? NSURL)?.apply {
//                doThis.invoke(P2pTempFolder(this))
//            }
//        } catch (e: Exception) {
//            loggy(e.stackTraceToString())
//        }
//
//        filepickerTold = {
//            saveReceivedPlaylist(it)
//        }
//    }
//
//    @OptIn(ExperimentalForeignApi::class)
//    private fun saveReceivedPlaylist(dest: NSURL) {
//        val access = dest.startAccessingSecurityScopedResource()
//        val manager = NSFileManager.defaultManager
//
//        coroutineP2p.launch {
//            try {
//                for (song in receivedSongs) {
//                    val accessSong = song.url.startAccessingSecurityScopedResource()
//
//                    val desti = dest.URLByAppendingPathComponent(
//                        song.url.absoluteString?.substringAfterLast("/") ?: "${(0..10000).random()}_song.mp3"
//                    )
//
//                    loggy("OPERATION STRIX (SAVING): Saving file to $desti (Folder: $dest)")
//
//                    if (desti != null) {
//                        val moving = manager.moveItemAtURL(
//                            srcURL = song.url,
//                            toURL = desti,
//                            null
//                        )
//
//                        if (moving) {
//                            val outcome = desti.makeGmixSong(dest)
//                            bookmarkSong(outcome)
//
//                            val songNotAdded = viewmodel.playlist.firstOrNull { it.uri == outcome.uri } == null
//                            if (songNotAdded) {
//                                viewmodel.playlist.add(outcome)
//                            }
//                        }
//                    }
//
//                    if (accessSong) {
//                        song.url.stopAccessingSecurityScopedResource()
//                    }
//                }
//                snapshotPlaylist()
//            } catch (e: Exception) {
//                loggy("Error saving playlist: ${e.stackTraceToString()}")
//            }
//
//            if (access) {
//                dest.stopAccessingSecurityScopedResource()
//            }
//        }
//    }
//
//    override fun stopP2pOperations() {
//        mcAdvertiser.stopAdvertisingPeer()
//        mcBrowser.stopBrowsingForPeers()
//
//        super.stopP2pOperations()
//    }
//
    data class PeerDevice(val peerId: MCPeerID, val peerName: String)
//
//
//    /** Cross platform */
//
//    override fun startCrossPlatform() {
//        p2pMode = P2pMode.CROSS_PLATFORM
//    }
//
//    fun connectCrossPlatform(host: String, port: Int) {
//        p2pMode = P2pMode.CROSS_PLATFORM
//
//        viewmodel.p2pQRpopup.value = false
//
//        coroutineP2p.launch(Dispatchers.IO) {
//            try {
//                loggy("Trying to connect to '${host}' on port '${port}")
//
//                val ktor = aSocket(SelectorManager(Dispatchers.IO))
//                    .tcp()
//                    .connect(host, port)
//
//                //Successfully connected by QR code
//                val connection = ktor.connection()
//
//                loggy("Successfully connected!")
//
//                if (viewmodel.playlist.isEmpty()) {
//                    p2pInput = connection.input.toRawSource()
//                    connection.input.awaitContent()
//                } else {
//                    p2pCrossOutput = connection.output
//                }
//
//                carryOnP2P()
//
//            } catch (e: Exception) {
//                loggy(e.stackTraceToString())
//            }
//        }
//    }
//
//    private fun ByteReadChannel.toRawSource(): RawSource {
//        val src = this
//
//        return object : RawSource {
//            override fun close() {
//                runIgnoring { src.cancel() }
//            }
//
//            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
//                if (byteCount == 0L) return 0L
//
//                require(byteCount >= 0) { "byteCount ($byteCount) < 0" }
//
//                try {
//                    val ba = runBlocking { src.toByteArray(byteCount.toInt()) }
//
//                    for (b in ba) {
//                        sink.writeByte(b)
//                    }
//
//                    return ba.size.toLong()
//                } catch (e: AssertionError) {
//                    throw e
//                }
//            }
//        }
//    }
//
//    private suspend fun sendPlaylistCross() {
//        /** Sending mode */
//        p2pCrossOutput?.use {
//            /* Communicating structure */
//            val sendablePlaylist = viewmodel.playlist.filter { !it.isCloudItem }
//            val fileCount = sendablePlaylist.size
//            loggy("SENDING P2P: Sending $fileCount files in total")
//
//            /* Communicating Device name */
//            val dvc = getDeviceName().encodeToByteArray()
//            writeLong(dvc.size.toLong())
//            writeFully(dvc)
//
//            viewmodel.transferStatusText.value =
//                "Attempting to send list (${fileCount} items) to ${selectedPeer?.peerName() ?: crossPeer}"
//
//            viewmodel.textPeer1.value = crossPeer
//            viewmodel.textPeer2.value = "You (${getDeviceName()})"
//
//            writeInt(fileCount)
//
//            loggy("Sending playlist: 1. Using output and iterating on files...")
//
//            try {
//                for ((i, song) in sendablePlaylist.withIndex()) {
//                    val assumedProgress = sendablePlaylist.indexOf(song).toFloat() / (fileCount - 1).toFloat()
//                    viewmodel.transferProgressPrimary.floatValue = assumedProgress.coerceIn(0f, 1f)
//
//                    loggy("Sending playlist: 1. Reading file source...")
//
//                    val doLaster = CompletableDeferred<() -> Unit>()
//                    val songSource = songSource(song, doLaster).await()
//                    val bytecount = songSource.second
//                    val filename = songSource.third
//                    val input = songSource.first.buffered()
//                    try {
//                        viewmodel.transferStatusText.value =
//                            "Sending $filename - ${bytecount.div(1 * 1024 * 1024)} MBs.".let {
//                                if (i == 0) "$it\nThe transfer may look stuck at the beginning to gather more information for 2-3 minutes." else it
//                            }
//
//                        loggy("SENDING P2P: Sending $${filename} - Expected size: $bytecount")
//
//                        writeLong(bytecount) //Communicate the expected file size
//                        //write(input, bytecount)
//                        val ba = input.readByteArray()
//                        writeFully(ba)
//
//                        //Communicate the file name
//                        val fName = (filename).encodeToByteArray()
//                        writeLong(fName.size.toLong())
//                        writeFully(fName)
//                    } catch (e: Exception) {
//                        loggy(e.stackTraceToString())
//                    } finally {
//                        doLaster.await().invoke()
//                        input.close()
//                        continue
//                    }
//                }
//
//                viewmodel.transferStatusText.value = "Finished sending playlist."
//
//                viewmodel.transferProgressPrimary.floatValue = 1f
//                viewmodel.transferButtonPositive.value = true
//                viewmodel.transferButtonText.value = "Close window"
//                viewmodel.transferButtonIcon.value = Icons.Outlined.Cancel
//            } catch (e: Exception) {
//                loggy(e.stackTraceToString())
//                viewmodel.transferStatusText.value = "Error sending playlist: ${e.message}"
//
//                viewmodel.transferButtonPositive.value = false
//                viewmodel.transferButtonText.value = "Close window"
//                viewmodel.transferButtonIcon.value = Icons.Outlined.Cancel
//            } finally {
//                runIgnoring { flush() }
//                stopP2pOperations()
//            }
//        }
//
//    }

}