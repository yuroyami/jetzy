package jetzy.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.posix.uname
import platform.posix.utsname
import kotlin.math.roundToLong

actual val PreferablyIO = Dispatchers.IO

actual val platform = Platform.IOS

actual fun generateTimestampMillis(): Long {
    return (NSDate().timeIntervalSince1970 * 1000).roundToLong()
}

@OptIn(ExperimentalForeignApi::class)
actual fun getDeviceName(): String {
    // Get the hardware model identifier (e.g. "iPhone11,2" for iPhone XS)
    val systemInfo = nativeHeap.alloc<utsname>()
    uname(systemInfo.ptr)
    val machine = systemInfo.machine.toKString()
    nativeHeap.free(systemInfo)

    val modelName = iosModelName(machine)
    val userName = UIDevice.currentDevice.name

    // If user hasn't customized their name (it matches the generic model),
    // return the detailed model name. Otherwise return the user-set name.
    return if (userName == "iPhone" || userName == "iPad" || userName == "iPod touch") {
        modelName
    } else {
        userName
    }
}

private fun iosModelName(machine: String): String = when {
    // iPhone
    machine.startsWith("iPhone17") -> when (machine) {
        "iPhone17,1" -> "iPhone 16 Pro"; "iPhone17,2" -> "iPhone 16 Pro Max"
        "iPhone17,3" -> "iPhone 16"; "iPhone17,4" -> "iPhone 16 Plus"
        "iPhone17,5" -> "iPhone 16e"; else -> "iPhone 16 series"
    }
    machine.startsWith("iPhone16") -> when (machine) {
        "iPhone16,1" -> "iPhone 15 Pro"; "iPhone16,2" -> "iPhone 15 Pro Max"
        "iPhone16,3" -> "iPhone 15"; "iPhone16,4" -> "iPhone 15 Plus"
        else -> "iPhone 15 series"
    }
    machine.startsWith("iPhone15") -> when (machine) {
        "iPhone15,2" -> "iPhone 14 Pro"; "iPhone15,3" -> "iPhone 14 Pro Max"
        "iPhone15,4" -> "iPhone 14"; "iPhone15,5" -> "iPhone 14 Plus"
        else -> "iPhone 14 series"
    }
    machine.startsWith("iPhone14") -> when (machine) {
        "iPhone14,2" -> "iPhone 13 Pro"; "iPhone14,3" -> "iPhone 13 Pro Max"
        "iPhone14,4" -> "iPhone 13 mini"; "iPhone14,5" -> "iPhone 13"
        "iPhone14,7" -> "iPhone 14"; "iPhone14,8" -> "iPhone 14 Plus"
        else -> "iPhone 13 series"
    }
    machine.startsWith("iPhone13") -> when (machine) {
        "iPhone13,1" -> "iPhone 12 mini"; "iPhone13,2" -> "iPhone 12"
        "iPhone13,3" -> "iPhone 12 Pro"; "iPhone13,4" -> "iPhone 12 Pro Max"
        else -> "iPhone 12 series"
    }
    machine.startsWith("iPhone12") -> when (machine) {
        "iPhone12,1" -> "iPhone 11"; "iPhone12,3" -> "iPhone 11 Pro"
        "iPhone12,5" -> "iPhone 11 Pro Max"; "iPhone12,8" -> "iPhone SE (2nd gen)"
        else -> "iPhone 11 series"
    }
    machine.startsWith("iPhone11") -> when (machine) {
        "iPhone11,2" -> "iPhone XS"; "iPhone11,4", "iPhone11,6" -> "iPhone XS Max"
        "iPhone11,8" -> "iPhone XR"
        else -> "iPhone XS series"
    }
    machine.startsWith("iPhone10") -> when (machine) {
        "iPhone10,1", "iPhone10,4" -> "iPhone 8"
        "iPhone10,2", "iPhone10,5" -> "iPhone 8 Plus"
        "iPhone10,3", "iPhone10,6" -> "iPhone X"
        else -> "iPhone X series"
    }
    // iPad
    machine.startsWith("iPad") -> "iPad"
    // Simulator
    machine == "x86_64" || machine == "arm64" -> "${UIDevice.currentDevice.model} (Simulator)"
    else -> UIDevice.currentDevice.model
}