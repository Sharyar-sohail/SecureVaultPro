package com.securevault.pro.data.imports

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The one folder grant the vault keeps in order to read WhatsApp's own files.
 *
 * A tree permission survives a restart, so the hub can re-scan the folder without making the
 * user find it again — but the grant can be revoked from system settings at any time, so
 * [current] checks the live permission instead of trusting the saved string. A stale grant
 * would otherwise show a folder full of chats and then fail on every tap.
 */
object WhatsAppAccess {

    private const val PREFS = "whatsapp_folder_access"
    private const val KEY_TREE = "tree_uri"
    private const val KEY_PACKAGE = "package_id"
    private const val KEY_LABEL = "folder_label"

    /** [label] is the folder's own name, shown so the user can see which app will be read. */
    data class Grant(val treeUri: Uri, val packageId: String, val label: String)

    fun remember(context: Context, treeUri: Uri): Grant? {
        if (treeUri.scheme != "content") return null
        val path = treeUri.encodedPath?.substringAfter("/tree/").orEmpty()
        val grant = Grant(
            treeUri = treeUri,
            packageId = packageFor(path) ?: WhatsAppPackages.MAIN,
            label = labelFor(path)
        )
        // Asking for a persistable permission on a URI the picker did not hand over throws;
        // the scan still works for this run, so the failure is not fatal and is not reported.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        store(context).edit()
            .putString(KEY_TREE, treeUri.toString())
            .putString(KEY_PACKAGE, grant.packageId)
            .putString(KEY_LABEL, grant.label)
            .commit()
        return grant
    }

    fun forget(context: Context) {
        val saved = store(context).getString(KEY_TREE, null)
        if (saved != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(saved),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        store(context).edit().clear().commit()
    }

    /** The saved folder, or null when it was never granted or has since been revoked. */
    fun current(context: Context): Grant? {
        val disk = store(context)
        val saved = disk.getString(KEY_TREE, null) ?: return null
        val treeUri = runCatching { Uri.parse(saved) }.getOrNull() ?: return null
        val live = runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission
            }
        }.getOrDefault(false)
        if (!live) {
            forget(context)
            return null
        }
        return Grant(
            treeUri = treeUri,
            packageId = disk.getString(KEY_PACKAGE, null) ?: WhatsAppPackages.MAIN,
            label = disk.getString(KEY_LABEL, null) ?: labelFor(saved)
        )
    }

    /**
     * Which WhatsApp the granted folder belongs to. The tree path is the only reliable witness:
     * both packages keep the same folder names, so the package id in the path is what tells
     * "WhatsApp" from "WhatsApp Business".
     */
    fun packageFor(treePath: String): String? {
        val path = decode(treePath)
        return WhatsAppPackages.launchable.firstOrNull { (id, _) -> path.contains("/$id/") }?.first
    }

    /** The folder's own name, for the dialog to say what it is about to read. */
    fun labelFor(treePath: String): String {
        val last = decode(treePath).trimEnd('/').substringAfterLast('/').substringAfterLast(':').trim()
        return if (last.isBlank()) "WhatsApp folder" else last
    }

    private fun decode(value: String): String =
        value.replace("%3A", ":").replace("%2F", "/").replace("%20", " ")

    private fun store(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
