package com.securevault.pro.presentation.screens.email

import com.securevault.pro.data.email.EmailPresets
import com.securevault.pro.data.email.MailEndpoint
import com.securevault.pro.data.email.ProviderPreset
import com.securevault.pro.data.email.SecurityMode
import com.securevault.pro.data.email.repository.MailAccountRecord
import com.securevault.pro.data.email.repository.MailCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manual setup form and the compose form, before either touches a socket or a store.
 *
 * The properties asserted here are the ones the screens promise: a template never blocks a hand
 * edit, a test with no password is refused with a sentence rather than a guaranteed server failure,
 * and nothing the owner types into either form is carried anywhere it would be shown back.
 */
class EmailSetupDraftTest {

    private val complete = MailSetupDraft(
        mode = SetupMode.MANUAL,
        address = "owner@example.com",
        username = "owner@example.com",
        password = "correct horse battery staple",
        incoming = MailEndpoint("imap.example.com", 993, SecurityMode.SSL_TLS),
        outgoing = MailEndpoint("smtp.example.com", 465, SecurityMode.SSL_TLS)
    )

    @Test
    fun `a complete manual form can be tested`() {
        assertTrue(complete.problems().toString(), complete.canTest)
    }

    @Test
    fun `an empty or malformed address is named before any connection is attempted`() {
        assertTrue(complete.copy(address = "").problems().any { it.contains("full email address") })
        assertTrue(complete.copy(address = "owner@example").problems().any { it.contains("full email address") })
        assertTrue(complete.copy(address = "own er@example.com").problems().any { it.contains("full email address") })
    }

    @Test
    fun `a missing host or an impossible port is a setup problem, not a timeout`() {
        val noHosts = complete.copy(
            incoming = complete.incoming.copy(host = ""),
            outgoing = complete.outgoing.copy(host = "")
        )
        assertTrue(noHosts.problems().any { it.contains("Incoming server is empty") })
        assertTrue(noHosts.problems().any { it.contains("Outgoing server is empty") })

        val badPort = complete.copy(outgoing = complete.outgoing.copy(port = 0))
        assertTrue(badPort.problems().any { it.contains("port must be between 1 and 65535") })
    }

    @Test
    fun `a test with no password is refused rather than failing at the server`() {
        val blank = complete.copy(password = "")
        assertFalse(blank.canTest)
        assertEquals(
            "Enter the password or app password for this mailbox.",
            blank.problems().last()
        )
    }

    @Test
    fun `a suggested template fills both endpoints and a hand edit overrules either one`() {
        val gmail = EmailPresets.suggestFor("owner@gmail.com")!!
        val filled = MailSetupDraft(address = "owner@gmail.com").applyTemplate(gmail, gmail.note)

        assertEquals("imap.gmail.com", filled.incoming.host)
        assertEquals(993, filled.incoming.port)
        assertEquals("smtp.gmail.com", filled.outgoing.host)
        assertEquals(ProviderPreset.GMAIL, filled.preset)
        assertTrue(filled.suggestion.contains("change any field"))

        val edited = filled.setEndpoint(EndpointSide.OUTGOING, "smtp.example.com", 587, SecurityMode.STARTTLS)
        assertEquals("imap.gmail.com", edited.incoming.host)
        assertEquals("smtp.example.com", edited.outgoing.host)
        assertEquals(587, edited.outgoing.port)
        assertEquals(SecurityMode.STARTTLS, edited.outgoing.security)
        assertEquals("a hand edit still says which template it started from", ProviderPreset.GMAIL, edited.preset)
        assertEquals("", edited.suggestion)
    }

    @Test
    fun `changing the security mode keeps the host and port the owner typed`() {
        val edited = complete.setSecurity(EndpointSide.INCOMING, SecurityMode.STARTTLS)
        assertEquals("imap.example.com", edited.incoming.host)
        assertEquals(993, edited.incoming.port)
        assertEquals(SecurityMode.STARTTLS, edited.incoming.security)
    }

    @Test
    fun `the stored account keeps the caller's id and none of the typed secret`() {
        val account = complete.toAccount("mail-123")

        assertEquals("mail-123", account.id)
        assertEquals("owner@example.com", account.address)
        assertEquals("imap.example.com", account.config.incoming.host)
        assertFalse(account.toString().contains(complete.password))

        // …and the row a store would build from it has no field holding the password either.
        val row = MailAccountRecord(account, complete.loginName, MailCredentials.NO_SEALED_CREDENTIAL)
        assertFalse(row.toString().contains(complete.password))
        assertTrue(row.needsPassword)
    }

    @Test
    fun `a blind copy is refused with the reason instead of being silently dropped`() {
        val draft = MailComposeDraft(to = "a@example.com", bcc = "b@example.com")
        assertFalse(draft.canSend)
        assertTrue(draft.problems().any { it.contains("blind copy") })
    }

    @Test
    fun `invalid recipients are named, and an empty To line is refused`() {
        assertTrue(MailComposeDraft().problems().any { it.contains("at least one recipient") })

        val broken = MailComposeDraft(to = "good@example.com", cc = "not-an-address")
        assertTrue(broken.problems().any { it.contains("not-an-address") })
    }

    @Test
    fun `the compose draft maps every field it owns onto the wire draft`() {
        val draft = MailComposeDraft(
            to = "one@example.com, two@example.com",
            cc = "cc@example.com",
            subject = "  Hello  ",
            body = "Body text",
            attachments = listOf(StagedAttachment("a.pdf", "/cache/a.pdf", 10))
        ).toDraft("owner@example.com")

        assertEquals("owner@example.com", draft.fromAddress)
        assertEquals(listOf("one@example.com", "two@example.com"), draft.to)
        assertEquals(listOf("cc@example.com"), draft.cc)
        assertEquals("Hello", draft.subject)
        assertEquals("Body text", draft.bodyText)
        assertEquals(listOf("/cache/a.pdf"), draft.attachmentPaths)
    }

    @Test
    fun `address rules accept a plain address and reject what is not one`() {
        assertTrue(MailAddressRules.looksLikeAddress(" owner@example.com "))
        assertFalse(MailAddressRules.looksLikeAddress("owner"))
        assertFalse(MailAddressRules.looksLikeAddress("owner@example"))
        assertFalse(MailAddressRules.looksLikeAddress("@example.com"))
        assertFalse(MailAddressRules.looksLikeAddress("own er@example.com"))
        assertFalse(MailAddressRules.looksLikeAddress("two@one@example.com"))

        assertEquals(
            listOf("a@example.com", "b@example.com", "c@example.com"),
            MailAddressRules.split("a@example.com, b@example.com;c@example.com\n")
        )
        assertEquals(listOf("bad"), MailAddressRules.rejected(listOf("bad", "ok@example.com")))
    }
}
