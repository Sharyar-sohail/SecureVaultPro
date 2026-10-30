package com.securevault.pro.data.imports

/**
 * The two WhatsApp packages the vault talks to. `com.whatsapp.w4b` is the real
 * WhatsApp Business id; the id that shipped before 3.1.9 (`com.whatsapp.wmbusiness`)
 * belongs to no app, so Business was invisible to package visibility and every
 * "open WhatsApp Business" tap looked like a dead button. These constants and the
 * manifest `<queries>` list are pinned against each other by WhatsAppImportTest.
 */
object WhatsAppPackages {
    const val MAIN: String = "com.whatsapp"
    const val BUSINESS: String = "com.whatsapp.w4b"

    /** Package id → the name to show the user. */
    val launchable: List<Pair<String, String>> = listOf(MAIN to "WhatsApp", BUSINESS to "WhatsApp Business")
}
