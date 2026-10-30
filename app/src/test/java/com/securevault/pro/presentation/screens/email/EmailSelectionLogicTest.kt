package com.securevault.pro.presentation.screens.email

import com.securevault.pro.data.email.MailFolder
import com.securevault.pro.data.email.MailMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder/selection/open-message machine, which is the part of the mailbox a JVM test can hold.
 *
 * The one rule every case here guards: a UID means a message *inside one folder*, so no transition
 * that changes the folder may leave a selection or an open message behind. The sockets are the
 * repository's business; this file only asserts that the screen never asks the server to act on the
 * previous folder's ids.
 */
class EmailSelectionLogicTest {

    private fun folder(wireName: String, flags: List<String> = emptyList()) = MailFolder(
        accountId = "mail-1",
        wireName = wireName,
        name = wireName,
        separator = "/",
        flags = flags,
        total = 1,
        unseen = 0
    )

    private fun message(uid: Long, seen: Boolean = false) = MailMessage(
        id = "msg-$uid",
        accountId = "mail-1",
        folderWireName = "INBOX",
        uid = uid,
        subject = "Subject $uid",
        fromName = "Sender",
        fromAddress = "sender@example.com",
        toSummary = "owner@example.com",
        receivedAt = 1_700_000_000_000L + uid,
        sizeOctets = 512,
        seen = seen,
        flagged = false,
        hasAttachments = false,
        attachmentCount = 0,
        snippet = ""
    )

    private fun listing(vararg names: String, extra: MailFolder? = null) =
        MailboxSelection(accountId = "mail-1", folderWireName = "INBOX")
            .withFolders(names.map { folder(it) } + listOfNotNull(extra))

    @Test
    fun `switching folders drops the selection and the open message`() {
        val before = listing("INBOX", "Archive")
            .copy(messages = listOf(message(1), message(2)), selectedUids = setOf(1L), openUid = 1L)
            .typedQuery("invoice")

        val after = before.selectFolder("Archive")

        assertEquals("Archive", after.folderWireName)
        assertTrue(after.messages.isEmpty())
        assertTrue(after.selectedUids.isEmpty())
        assertNull(after.openUid)
        assertEquals("", after.query)
        assertFalse(after.searching)
    }

    @Test
    fun `re-selecting the folder already open is a no-op`() {
        val current = listing("INBOX", "Archive")
        assertSame(current, current.selectFolder("INBOX"))
    }

    @Test
    fun `a folder that vanished falls back to INBOX and never keeps stale rows`() {
        val before = listing("INBOX", "Archive")
            .copy(
                folderWireName = "Archive",
                messages = listOf(message(1)),
                selectedUids = setOf(1L),
                openUid = 1L
            )

        val after = before.withFolders(listOf(folder("INBOX"), folder("Trash")))

        assertEquals("INBOX", after.folderWireName)
        assertTrue(after.messages.isEmpty())
        assertTrue(after.selectedUids.isEmpty())
        assertNull(after.openUid)
    }

    @Test
    fun `a noselect container is not offered and cannot become current`() {
        val noselect = folder("All", listOf("\\Noselect"))
        val after = listing("INBOX").withFolders(listOf(noselect, folder("INBOX")))

        assertEquals(listOf("INBOX"), after.folders.map { it.wireName })
        assertTrue(after.moveTargets.isEmpty())
    }

    @Test
    fun `a fresh listing drops selected uids the server no longer lists`() {
        val before = listing("INBOX")
            .copy(messages = listOf(message(1)), selectedUids = setOf(1L, 2L), openUid = 2L)

        val after = before.withMessages(listOf(message(1), message(3)))

        assertEquals(setOf(1L), after.selectedUids)
        assertNull(after.openUid)
    }

    @Test
    fun `select all picks exactly the listed window and a second press clears it`() {
        val state = listing("INBOX").withMessages(listOf(message(1), message(2), message(3)))

        val all = state.selectAll()
        assertEquals(setOf(1L, 2L, 3L), all.selectedUids)
        assertTrue(all.allSelected)

        assertTrue(all.selectAll().selectedUids.isEmpty())
    }

    @Test
    fun `a wider window only grows and stops at the cap`() {
        val first = MailboxSelection()
        assertEquals(MailboxSelection.FIRST_WINDOW, first.limit)
        assertEquals(MailboxSelection.FIRST_WINDOW + MailboxSelection.MORE_WINDOW, first.loadWider().limit)

        val atCap = MailboxSelection(limit = MailboxSelection.MAX_WINDOW)
        assertEquals(MailboxSelection.MAX_WINDOW, atCap.loadWider().limit)
        assertEquals(MailboxSelection.MAX_WINDOW, MailboxSelection(limit = 490).loadWider().limit)
    }

    @Test
    fun `move targets are the other folders, never the one the messages are in`() {
        val state = listing("INBOX", "Archive", "Trash")
            .withMessages(listOf(message(1)))
            .selectAll()

        assertEquals(listOf("Archive", "Trash"), state.moveTargets.map { it.wireName })
        assertTrue(state.canMove)

        val oneFolder = listing("INBOX").withMessages(listOf(message(1))).selectAll()
        assertFalse(oneFolder.canMove)
    }

    @Test
    fun `a search is only a search while a query is in force`() {
        val typing = MailboxSelection(searching = true)
        assertFalse(typing.isSearching)
        assertTrue(typing.typedQuery("from:bank").isSearching)
        assertFalse(typing.typedQuery("  ").isSearching)
    }

    @Test
    fun `pointing at another account resets everything but the window size`() {
        val first = MailboxSelection(
            accountId = "mail-1",
            folderWireName = "INBOX",
            messages = listOf(message(1)),
            selectedUids = setOf(1L),
            openUid = 1L,
            query = "x",
            searching = true,
            limit = 150
        )

        val second = first.forAccount("mail-2")
        assertEquals("mail-2", second.accountId)
        assertEquals(150, second.limit)
        assertEquals("", second.folderWireName)
        assertTrue(second.messages.isEmpty())
        assertTrue(second.selectedUids.isEmpty())
        assertNull(second.openUid)
        assertEquals("", second.query)
        assertFalse(second.searching)

        assertSame(first, first.forAccount("mail-1"))
    }

    @Test
    fun `the unread count is what the listing says, not what a selection implies`() {
        val state = MailboxSelection(
            messages = listOf(message(1, seen = true), message(2), message(3))
        )
        assertEquals(2, state.unseenCount)
    }
}
