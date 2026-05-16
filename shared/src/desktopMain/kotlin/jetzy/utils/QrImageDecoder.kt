package jetzy.utils

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.imageio.ImageIO

/**
 * Pop an AWT file picker, read the chosen image, and ask ZXing to find a QR.
 * Lives outside any composable so the UI layer doesn't transitively depend on
 * java.awt / ZXing.
 */
object QrImageDecoder {

    fun pickAndDecode(): String? {
        val file = pickImageFile() ?: return null
        val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        val source = BufferedImageLuminanceSource(image)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }
        return try {
            reader.decode(bitmap).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun pickImageFile(): File? {
        val dialog = FileDialog(null as Frame?, "Pick QR image", FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.lowercase().endsWithOneOf(".png", ".jpg", ".jpeg", ".bmp", ".gif") }
            isVisible = true
        }
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return File(dir, name).takeIf { it.exists() }
    }

    private fun String.endsWithOneOf(vararg suffixes: String): Boolean =
        suffixes.any { endsWith(it) }
}
