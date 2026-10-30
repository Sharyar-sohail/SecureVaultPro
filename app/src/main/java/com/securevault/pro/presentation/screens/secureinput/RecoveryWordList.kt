package com.securevault.pro.presentation.screens.secureinput

import android.content.Context
import com.securevault.pro.presentation.screens.secureinput.RecoverySeedLogic.WORDLIST_SIZE
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The BIP-39 English list, from the same asset the crypto wallets already validate against
 * (`assets/bip39_english.txt`, 2048 lines). One loader for the seed flow so a screen never parses
 * a file on the main thread and a test never has to.
 *
 * [words] is only safe to touch off the main thread — the first read hits the APK assets.
 */
@Singleton
class RecoveryWordList @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val words: List<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            context.assets.open(ASSET).bufferedReader().use { reader ->
                reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }

    /** True only when the asset read produced a complete list; a short list would silently accept fewer words. */
    val isReady: Boolean get() = words.size == WORDLIST_SIZE
}

private const val ASSET = "bip39_english.txt"
