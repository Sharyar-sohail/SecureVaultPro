package com.securevault.pro.data.email

/**
 * Every number the mail module refuses to improvise about.
 *
 * The rule behind the list: a network path that can hang must be able to be *bounded* and
 * *escaped*. Each socket therefore gets a connect timeout, a read timeout and a coroutine
 * deadline, and each of the three can fire on its own — the coroutine deadline is what lets the
 * screen's Cancel button work even when the socket is wedged in a syscall.
 */
object MailTimings {

    /** TCP connect: generous for mobile data, short enough that a dead host is not a frozen screen. */
    const val CONNECT_TIMEOUT_MS = 15_000

    /** Per-read socket timeout: a server that stops mid-response is a failure, not a hang. */
    const val SOCKET_READ_TIMEOUT_MS = 30_000

    /** Whole-command deadline (greeting + one command + its reply). */
    const val COMMAND_TIMEOUT_MS = 45_000

    /** The "test configuration" handshake on the setup screen: login + CAPABILITY + LIST, bounded. */
    const val VALIDATION_TIMEOUT_MS = 40_000

    /** One folder's fetch run, including body streaming. */
    const val SYNC_TIMEOUT_MS = 180_000

    /** Auto-sync cadence while the vault is unlocked and the toggle is on. */
    const val AUTO_SYNC_INTERVAL_MS = 15L * 60L * 1000L

    /** How many new UIDs one sync round will fetch before yielding to the owner. */
    const val MAX_NEW_MESSAGES_PER_SYNC = 60

    /** Header/envelope metadata fetched per message; bodies come only when the message is opened. */
    const val METADATA_UID_BATCH = 25

    /**
     * Line-buffer ceiling. A header line over this is a protocol error (or an attack), so the
     * reader refuses instead of growing without limit.
     */
    const val MAX_LINE_BYTES = 64 * 1024

    /**
     * The one streaming chunk size of the module: bodies, attachments and SMTP DATA all move
     * through buffers of exactly this many bytes, so peak memory is a constant no matter how
     * large the message is.
     */
    const val STREAM_CHUNK_BYTES = 32 * 1024

    /** Largest `{n}` literal the reader will accept for a *metadata* response (envelope, structure). */
    const val MAX_INLINE_LITERAL_BYTES = 4L * 1024L * 1024L

    /**
     * Above this the literal is streamed straight to the app cache instead of being kept, which is
     * what keeps a 25 MB attachment out of the heap.
     */
    const val STREAMED_LITERAL_THRESHOLD_BYTES = 256L * 1024L

    /** Snippet/preview ceiling: how much decoded text is ever held for the list and reader rows. */
    const val SNIPPET_CHARS = 480

    /** Character count of decoded body text kept for the on-screen preview of an open message. */
    const val BODY_PREVIEW_CHARS = 60 * 1024

    /** Guards against a hostile or broken MIME tree: parts per message and tree depth. */
    const val MAX_MIME_PARTS = 512
    const val MAX_MIME_DEPTH = 16

    /** Compose limits, so the SMTP writer never has to hold an unbounded string. */
    const val MAX_BODY_CHARS_FOR_INLINE_TEXT = 2 * 1024 * 1024
}
