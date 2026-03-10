package jetzy.p2p

import jetzy.viewmodel.JetzyViewmodel

abstract class P2pHandler(open val viewmodel: JetzyViewmodel) {

    var selectedPeer: P2pPeer? = null
    //var crossPeer: String = "iPhone"
    //var isP2Ptransferring = false

    /* The Input and Output streams (Okio/Kotlinx.io) */
    //var p2pInput: RawSource? = null
    //var p2pOutput: RawSink? = null

    //var p2pCrossOutput: ByteWriteChannel? = null //only necessary for iOS-to-Android writing

    //val receivedSongs = mutableListOf<P2pReceivedFile>()

    var p2pMode: P2pMode? = null
    var p2pOperation: P2pOperation? = null

    open fun beginP2p(mode: P2pMode, operation: P2pOperation) {
        p2pMode = mode
        p2pOperation = operation
    }

    open fun connectNativePeer(peer: P2pPeer) {
        selectedPeer = peer
    }

    /** Needs to be overridden */
    open fun stopP2pOperations() {
        selectedPeer = null
        //isP2Ptransferring = false
        //runCatching { p2pInput?.close(); p2pInput = null }
        //runCatching { p2pOutput?.close(); p2pOutput = null }
    }

    /*
    protected suspend fun sendPlaylist() {
        /** Sending mode */
        p2pOutput?.buffered()?.let { output ->
            /* Communicating structure */

            val sendablePlaylist = listOf<String>() //TODO
            val fileCount = sendablePlaylist.size
            loggy("SENDING P2P: Sending $fileCount files in total")

            if (p2pMode == P2pMode.CROSS_PLATFORM) {
                val dvc = getDeviceName().encodeToByteArray()
                output.writeLong(dvc.size.toLong())
                output.write(dvc)
            }

            viewmodel.textPeer1.value = selectedPeer?.peerName() ?: crossPeer
            viewmodel.textPeer2.value = "You (${getDeviceName()})"

            viewmodel.transferStatusText.value = "Attempting to send list (${fileCount} items) to ${selectedPeer?.peerName() ?: crossPeer}"

            output.writeInt(fileCount)

            loggy("Sending playlist: 1. Using output and iterating on files...")

            try {
                for ((i, song) in sendablePlaylist.withIndex()) {
                    val assumedProgress = sendablePlaylist.indexOf(song).toFloat() / (fileCount - 1).toFloat()
                    viewmodel.transferProgressPrimary.floatValue = assumedProgress.coerceIn(0f, 1f)

                    loggy("Sending playlist: 1. Reading file source...")

                    val doLaster = CompletableDeferred<() -> Unit>()
                    val songSource = songSource(song, doLaster).await()
                    val bytecount = songSource.second
                    val filename = songSource.third
                    val input = songSource.first.buffered()
                    try {
                        viewmodel.transferStatusText.value = "Sending $filename - ${bytecount.div(1 * 1024 * 1024)} MBs.".let {
                            if (i == 0) "$it\nThe transfer may look stuck at the beginning to gather more information for 2-3 minutes." else it
                        }

                        loggy("SENDING P2P: Sending $${filename} - Expected size: $bytecount")

                        output.writeLong(bytecount) //Communicate the expected file size
                        output.write(input, bytecount)

                        //Communicate the file name
                        val fName = (filename).encodeToByteArray()
                        output.writeLong(fName.size.toLong())
                        output.write(fName)
                    } catch (e: Exception) {
                        loggy(e.stackTraceToString())
                    } finally {
                        doLaster.await().invoke()
                        input.close()
                        continue
                    }
                }

                viewmodel.transferStatusText.value = "Finished sending playlist."

                viewmodel.transferProgressPrimary.floatValue = 1f
            } catch (e: Exception) {
                loggy(e.stackTraceToString())
                viewmodel.transferStatusText.value = "Error sending playlist: ${e.message}"
            } finally {
                runCatching { output.emit() }
                stopP2pOperations()
            }
        }
    }

    protected suspend fun receivePlaylist() {
        /** Listening (Reception) mode */
        var finalCount = 0

        withinTempFolder {
            p2pInput?.buffered()?.let { input ->
                receivedSongs.clear()

                try {
                    if (p2pMode == P2pMode.CROSS_PLATFORM) {
                        val pL = input.readLong()
                        val pName = input.readByteArray(pL.toInt()).decodeToString()
                        crossPeer = pName
                    }

                    viewmodel.textPeer1.value = selectedPeer?.peerName() ?: crossPeer
                    viewmodel.textPeer2.value = "You (${getDeviceName()})"

                    viewmodel.transferStatusText.value = "Attempting to receive list from ${selectedPeer?.peerName() ?: crossPeer}"

                    finalCount = input.readInt()

                    viewmodel.transferStatusText.value = "Expecting $finalCount items.".also { loggy(it) }

                    for (i in (0 until finalCount)) {
                        viewmodel.transferProgressPrimary.floatValue = ((i + 1).toDouble() / finalCount.toDouble())
                            .toFloat().coerceIn(0f, 1f)

                        val songSink = songSink(tempName = "$i", scope = this@withinTempFolder).await()
                        val songProduct = songSink.second
                        val output = songSink.first.buffered()
                        try {
                            val fSize = input.readLong()

                            loggy("File $i - size: ${fSize.div(1024 * 1024)} MBs")
                            viewmodel.transferStatusText.value = "Receiving file ($i/$finalCount) - ${fSize.div(1 * 1024 * 1024)} MBs."

                            if (fSize == -1L) {
                                loggy("Error detected (-1), continuing...")
                                continue
                            }

                            output.write(input, fSize)
                        } catch (e: Exception) {
                            loggy(e.stackTraceToString())
                            continue
                        }

                        val fNameSize = input.readLong()
                        val fName = input.readByteArray(fNameSize.toInt()).decodeToString()
                        loggy("Filename: $fName")

                        viewmodel.transferStatusText.value = "Successfully received file No.$i: $fName"

                        songProduct?.let {
                            val songFinal = songRename("$i", scope = this@withinTempFolder, it, finalName = fName).await()
                            receivedSongs.add(songFinal)
                        }
                        output.close()
                    }
                } catch (e: Exception) {
                    loggy(e.stackTraceToString())
                    viewmodel.transferStatusText.value = "Error receiving playlist: ${e.message}"
                }
            }

            /* Transfer file ended */
            if (receivedSongs.isNotEmpty()) {

                val text = "Finished receiving playlist.\n${receivedSongs.size} files received in total."
                if (receivedSongs.size < finalCount) {
                    viewmodel.transferStatusText.value = "$text\nThere may have been a problem receiving playlist. You can still save the received songs."
                } else {
                    viewmodel.transferStatusText.value = text
                }
            } else {
                viewmodel.transferStatusText.value = "Received nothing. Please try again."
            }
        }
    }*/
}