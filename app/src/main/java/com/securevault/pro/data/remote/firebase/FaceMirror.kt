package com.securevault.pro.data.remote.firebase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes which of the 15 themes the phone is currently painted in, so the browser face can wear
 * the same one.
 *
 * This is deliberately NOT part of the sync engine: it writes one plain string at
 * `svp1/users/$uid/meta/face`, outside every record node, so the vault, the revision counter and the
 * reconcile loop behave exactly as they did before. A theme id is not secret and not vault data —
 * it is the look the owner chose — so it travels in the clear, the same way `lastDevice` already does.
 *
 * A failure here is invisible on purpose. If the write cannot land, the web keeps whatever face it
 * last saw; nothing in the vault is affected and no sync is retried because of it.
 */
@Singleton
class FaceMirror @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: SvpAuth
) {

    /**
     * Best-effort publish of [themeId] under the signed-in uid. No uid means nobody is looking at a
     * browser mirror, so the call simply returns.
     */
    suspend fun publish(themeId: String) {
        val uid = auth.cachedUid() ?: return
        val ref = FirebaseBootstrap.reference(context, RealtimePaths.meta(uid))?.child(FACE_NODE) ?: return
        runCatching {
            withTimeout(PUBLISH_TIMEOUT_MS) { ref.setValue(themeId).await() }
        }
    }

    companion object {
        const val FACE_NODE = "face"
        private const val PUBLISH_TIMEOUT_MS = 8_000L
    }
}
