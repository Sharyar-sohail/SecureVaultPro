package com.securevault.pro.data.backup

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the XML mirror that every backup has carried beside its JSON body.
 *
 * The writer has always emitted both copies inside the same sealed envelope, so a vault whose
 * JSON copy is missing or truncated still has every record here. Parsing it is what makes
 * `vault_backup.xml.enc` restorable instead of a file the walk used to skip.
 */
object BackupXmlParser {

    fun parse(xml: String): BackupPayloadDto? = runCatching {
        val document = factory().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = document.documentElement ?: return null
        if (root.tagName != "secureVault") return null
        BackupPayloadDto(
            version = root.attr("version")?.toIntOrNull() ?: 3,
            entries = children(root, "entries", "entry").mapNotNull(::entry),
            notes = children(root, "notes", "note").mapNotNull(::note),
            cards = children(root, "cards", "card").mapNotNull(::card),
            totps = children(root, "totpAccounts", "account").mapNotNull(::totp),
            contacts = children(root, "contacts", "contact").mapNotNull(::contact),
            files = children(root, "files", "file").mapNotNull(::file),
            cryptoWallets = children(root, "cryptoWallets", "wallet").mapNotNull(::wallet)
        )
    }.getOrNull()

    private fun factory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        // A backup never carries a DTD. Refuse one outright and keep external entities off, so a
        // hostile file cannot make the parser reach the filesystem or the network.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setXIncludeAware(false) }
    }

    private fun children(root: Element, container: String, tag: String): List<Element> {
        val parent = root.childElements().firstOrNull { it.tagName == container } ?: return emptyList()
        return parent.childElements().filter { it.tagName == tag }
    }

    private fun entry(e: Element) = BackupEntryDto(
        id = e.attr("id"),
        title = e.attr("title"),
        username = e.attr("username"),
        email = e.attr("email"),
        password = e.text("password"),
        website = e.attr("website"),
        url = e.attr("url"),
        notes = e.text("notes"),
        category = e.attr("category"),
        customFieldsJson = e.text("customFields"),
        tagsJson = e.text("tags"),
        isFavorite = e.attr("favorite")?.toBoolean(),
        hasTotp = e.attr("hasTotp")?.toBoolean(),
        totpSecret = e.text("totpSecret"),
        createdAt = e.attr("createdAt")?.toLongOrNull(),
        updatedAt = e.attr("updatedAt")?.toLongOrNull(),
        lastAccessedAt = e.attr("lastAccessedAt")?.toLongOrNull(),
        expiresAt = e.attr("expiresAt")?.toLongOrNull(),
        strengthScore = e.attr("strengthScore")?.toIntOrNull(),
        isHiddenInTravel = e.attr("hiddenInTravel")?.toBoolean()
    )

    private fun note(e: Element) = BackupNoteDto(
        id = e.attr("id"),
        title = e.attr("title"),
        content = e.text("content"),
        isPinned = e.attr("pinned")?.toBoolean(),
        color = e.attr("color"),
        tagsJson = e.text("tags"),
        createdAt = e.attr("createdAt")?.toLongOrNull(),
        updatedAt = e.attr("updatedAt")?.toLongOrNull(),
        attachmentsJson = e.text("attachments")
    )

    private fun card(e: Element) = BackupCardDto(
        id = e.attr("id"),
        type = e.attr("type"),
        nickname = e.attr("nickname"),
        holderName = e.attr("holderName"),
        number = e.text("number"),
        expiryMonth = e.attr("expiryMonth")?.toIntOrNull(),
        expiryYear = e.attr("expiryYear")?.toIntOrNull(),
        cvv = e.text("cvv"),
        pin = e.text("pin"),
        bankName = e.attr("bankName"),
        notes = e.text("notes"),
        colorGradientStart = e.attr("colorGradientStart"),
        colorGradientEnd = e.attr("colorGradientEnd"),
        createdAt = e.attr("createdAt")?.toLongOrNull()
    )

    private fun totp(e: Element) = BackupTotpDto(
        id = e.attr("id"),
        issuer = e.attr("issuer"),
        accountName = e.attr("accountName"),
        secret = e.text("secret"),
        algorithm = e.attr("algorithm"),
        digits = e.attr("digits")?.toIntOrNull(),
        period = e.attr("period")?.toIntOrNull(),
        createdAt = e.attr("createdAt")?.toLongOrNull()
    )

    private fun contact(e: Element) = BackupContactDto(
        id = e.attr("id"),
        name = e.attr("name"),
        phone = e.text("phone"),
        email = e.text("email"),
        address = e.text("address"),
        notes = e.text("notes"),
        category = e.attr("category"),
        createdAt = e.attr("createdAt")?.toLongOrNull(),
        updatedAt = e.attr("updatedAt")?.toLongOrNull()
    )

    private fun file(e: Element) = BackupFileDto(
        id = e.attr("id"),
        name = e.attr("name"),
        size = e.attr("size")?.toLongOrNull(),
        type = e.attr("type"),
        mimeType = e.attr("mimeType"),
        category = e.attr("category"),
        data = e.text("data"),
        createdAt = e.attr("createdAt")?.toLongOrNull(),
        uploadedAt = e.attr("uploadedAt")?.toLongOrNull()
    )

    private fun wallet(e: Element) = BackupCryptoWalletDto(
        id = e.attr("id"),
        label = e.attr("label"),
        kind = e.attr("kind"),
        secret = e.text("secret"),
        keyBackup = e.text("keyBackup"),
        balancesJson = e.text("balances"),
        totalUsd = e.attr("totalUsd")?.toDoubleOrNull(),
        lastSyncedAt = e.attr("lastSyncedAt")?.toLongOrNull(),
        createdAt = e.attr("createdAt")?.toLongOrNull(),
        updatedAt = e.attr("updatedAt")?.toLongOrNull()
    )

    private fun Element.childElements(): List<Element> {
        val nodes = childNodes
        val out = ArrayList<Element>(nodes.length)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) out.add(node as Element)
        }
        return out
    }

    private fun Element.attr(name: String): String? =
        if (hasAttribute(name)) getAttribute(name).takeIf { it.isNotBlank() } else null

    private fun Element.text(name: String): String? =
        childElements().firstOrNull { it.tagName == name }?.textContent?.takeIf { it.isNotEmpty() }
}
