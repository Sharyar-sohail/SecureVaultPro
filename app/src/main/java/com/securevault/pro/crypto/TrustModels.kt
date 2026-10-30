package com.securevault.pro.crypto

/**
 * The shape of a pre-signing verdict. Lives in the crypto package because both the check builder
 * ([com.securevault.pro.data.wallet.TransactionTrust]) and the domain quote that carries it to the
 * confirmation screen need it, and neither should depend on the other's layer.
 *
 * A check that did not pass carries an [AddressRules.Code] naming the rule; that code is what the
 * send path tests and what the report shows. Nothing here can hold a key, a signature or a
 * credential — the fields are labels and reasons meant for the screen.
 */
data class TrustCheck(
    val label: String,
    val detail: String,
    val passed: Boolean,
    val code: AddressRules.Code? = null
)

data class TrustReport(val checks: List<TrustCheck>) {

    val blocking: List<TrustCheck> get() = checks.filter { !it.passed }
    val accepted: Boolean get() = blocking.isEmpty()

    /** The single sentence shown when a send is refused. */
    fun refusalReason(): String =
        blocking.firstOrNull()?.detail ?: "The transfer passed every check."

    fun shortReasons(): List<String> = blocking.map { it.label }

    companion object {
        val EMPTY = TrustReport(emptyList())
    }
}
