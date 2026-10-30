package com.securevault.pro.data.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class BloggerThemeFile(
    val content: String,
    val fileName: String
)

/**
 * Wraps the bundled Blogger Layouts v2 theme around a passphrase-sealed v3
 * envelope. Only salt, IV and ciphertext are copied into the theme; the
 * universal cloud key is never referenced.
 */
@Singleton
class BloggerThemeExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TEMPLATE_ASSET = "blogger_theme.xml"
        private val SEED_LINE = Regex("var SEED = \\{[^}]*\\};")
        private val TITLE_BLOCK = Regex("<h1 id='svp-title'>[^<]*</h1>")
    }

    fun build(
        envelopeBytes: ByteArray,
        driveClientId: String = "",
        title: String = "Secure Wallet Pro"
    ): String {
        val envelope = JSONObject(envelopeBytes.toString(Charsets.UTF_8))
        val salt = envelope.optString("salt")
        val data = envelope.optString("data")
        val parts = data.split('.', limit = 2)
        require(
            salt.isNotBlank() && parts.size == 2 &&
                parts[0].isNotBlank() && parts[1].isNotBlank()
        ) { "The encrypted export is not in envelope v3 format." }

        val template = context.assets.open(TEMPLATE_ASSET)
            .bufferedReader()
            .use { it.readText() }

        val seed = buildString {
            append("var SEED = { salt: '").append(jsString(salt)).append("', ")
            append("iv: '").append(jsString(parts[0])).append("', ")
            append("ct: '").append(jsString(parts[1])).append("', ")
            append("iters: 600000, ")
            append("clientId: '").append(jsString(driveClientId.trim())).append("' };")
        }
        val withSeed = template.replace(SEED_LINE) { seed }
        val safeTitle = jsString(title.trim().ifBlank { "Secure Wallet Pro" }.replace("&", "&amp;"))
        return withSeed.replace(TITLE_BLOCK) { "<h1 id='svp-title'>$safeTitle</h1>" }
    }

    /** Keeps injected values inside the script's single-quoted string literals. */
    private fun jsString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "")
        .replace("\n", " ")
        .replace("\${", "\\\${")
        .replace("`", "\\`")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
