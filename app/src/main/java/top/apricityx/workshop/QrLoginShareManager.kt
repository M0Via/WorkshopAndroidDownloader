package top.apricityx.workshop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream

/**
 * 【扫码登录】二维码生成 + 一键分享。
 *
 * 设计对齐 WorkshopFileShareManager：无状态 object，输出可直接使用的 Intent。
 * 二维码内容就是 Steam 下发的 challenge_url（形如 https://s.team/q/...），
 * 手机 Steam App 扫它即可向本 App 的登录会话授权。
 *
 * 关于"仅一次授权"：不在这里做任何凭据持久化逻辑。Steam 在手机端确认时
 * 会原生提供授权范围/时效选项，由用户在 Steam App 内决定，App 侧不越俎代庖。
 */
object QrLoginShareManager {
    private const val QR_FILE_NAME = "steam_login_qr.png"
    private const val QR_SIZE_PX = 768
    private const val SHARE_AUTHORITY_SUFFIX = ".fileprovider"

    /** 生成二维码位图。失败时返回 null，由调用方展示错误。 */
    fun generateQrBitmap(challengeUrl: String): Bitmap? {
        if (challengeUrl.isBlank()) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
                EncodeHintType.MARGIN to 2,
            )
            val matrix = QRCodeWriter().encode(
                challengeUrl,
                BarcodeFormat.QR_CODE,
                QR_SIZE_PX,
                QR_SIZE_PX,
                hints,
            )
            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        }.getOrNull()
    }

    /**
     * 把二维码写到 app 内部 exports/ 目录，返回 FileProvider content Uri。
     * exports/ 已在 workshop_file_paths.xml 的 app_internal_exports 中声明，
     * 因此可被 FileProvider 安全分享给微信/QQ/系统分享面板，让朋友扫码。
     */
    fun saveQrToShareUri(context: Context, bitmap: Bitmap): String? = runCatching {
        val exportsDir = File(context.applicationContext.filesDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, QR_FILE_NAME)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        val authority = context.applicationContext.packageName + SHARE_AUTHORITY_SUFFIX
        FileProvider.getUriForFile(context.applicationContext, authority, file).toString()
    }.getOrNull()

    /** 构造分享二维码的 ACTION_SEND Intent（图片），带 chooser 与读权限。 */
    fun createShareQrIntent(context: Context, contentUri: String): Intent? {
        val uri = runCatching { android.net.Uri.parse(contentUri) }.getOrNull() ?: return null
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Steam 扫码登录二维码")
            putExtra(Intent.EXTRA_TEXT, "请用手机 Steam App 扫描此二维码，为我的设备授权登录。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(shareIntent, "分享二维码给朋友扫码").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
