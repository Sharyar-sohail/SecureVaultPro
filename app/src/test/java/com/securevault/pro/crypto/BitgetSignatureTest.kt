package com.securevault.pro.crypto

import com.securevault.pro.data.wallet.BitgetClient
import com.securevault.pro.domain.model.BitgetFailureKind
import com.securevault.pro.domain.model.BitgetSecretBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-answer tests for the Bitget v2 signature.
 *
 * Every value below is fabricated for the test and is not, and could not be, a live credential:
 * the "secret" is the base64 of the ASCII text `fake-secret-value-for-test-only`, the key and the
 * passphrase say what they are, and the timestamp is one fixed instant (2023-11-14T22:13:20Z).
 *
 * The expected Base64 signatures are NOT computed by this file. They were produced independently
 * with `node:crypto`, and re-checked against `javax.crypto.Mac` on a desktop JVM, before being pasted
 * in as constants — so the test pins the algorithm (pre-hash order, key bytes, encoding) rather than
 * mirroring the implementation. If the signing code drifts, these constants stop matching.
 *
 * ```
 * node -e 'const{createHmac}=require("node:crypto");
 *   const s="ZmFrZS1zZWNyZXQtdmFsdWUtZm9yLXRlc3Qtb25seQ==";
 *   createHmac("sha256",Buffer.from(s,"utf8")).update("<pre-hash>","utf8").digest("base64")'
 * ```
 */
class BitgetSignatureTest {

    private val fakeApiKey = "FAKE-ACCESS-KEY-abcdef0123456789"
    private val fakeSecret = "ZmFrZS1zZWNyZXQtdmFsdWUtZm9yLXRlc3Qtb25seQ=="
    private val fakePassphrase = "fake-passphrase-for-tests-only"
    private val fixedTimestamp = "1700000000000"

    // ------------------------------------------------------------------ pre-hash layout

    @Test
    fun spotPreHashIsTimestampMethodPathWithNoQueryAndNoBody() {
        // Documented: if the queryString is empty, the signature content is
        // timestamp + method.toUpperCase() + requestPath + body — with no "?" at all.
        val preHash = BitgetClient.preHash(
            timestamp = fixedTimestamp,
            method = "GET",
            requestPath = "/api/v2/spot/account/assets",
            queryString = "",
            body = ""
        )
        assertEquals("1700000000000GET/api/v2/spot/account/assets", preHash)
    }

    @Test
    fun mixPreHashAddsOneQuestionMarkBeforeTheQueryString() {
        val preHash = BitgetClient.preHash(
            timestamp = fixedTimestamp,
            method = "GET",
            requestPath = "/api/v2/mix/account/accounts",
            queryString = "productType=usdt-futures",
            body = ""
        )
        assertEquals(
            "1700000000000GET/api/v2/mix/account/accounts?productType=usdt-futures",
            preHash
        )
    }

    @Test
    fun methodIsUppercasedAndBodyAppendsLast() {
        val preHash = BitgetClient.preHash(
            timestamp = fixedTimestamp,
            method = "post",
            requestPath = "/api/v2/mix/order/place-order",
            queryString = "productType=usdt-futures",
            body = "{\"productType\":\"usdt-futures\",\"size\":\"1\"}"
        )
        assertEquals(
            "1700000000000POST/api/v2/mix/order/place-order?productType=usdt-futures" +
                "{\"productType\":\"usdt-futures\",\"size\":\"1\"}",
            preHash
        )
    }

    @Test
    fun blankBodyIsOmittedAndBlankQueryAddsNothing() {
        val withBlank = BitgetClient.preHash(fixedTimestamp, "GET", "/api/v2/spot/account/assets", "", "   ")
        assertEquals("1700000000000GET/api/v2/spot/account/assets", withBlank)
    }

    // ------------------------------------------------------------------ known answers

    @Test
    fun spotAssetsSignatureMatchesThePinnedVector() {
        val preHash = BitgetClient.preHash(
            fixedTimestamp, "GET", "/api/v2/spot/account/assets", "", ""
        )
        assertEquals(
            "hrxVnDJES3n2S6RmJNVmMZrVs/ifpKcFoo3GXo4YXSQ=",
            BitgetClient.sign(preHash, fakeSecret)
        )
    }

    @Test
    fun mixAccountsSignatureMatchesThePinnedVector() {
        val preHash = BitgetClient.preHash(
            fixedTimestamp, "GET", "/api/v2/mix/account/accounts", "productType=usdt-futures", ""
        )
        assertEquals(
            "Tc0/oEiL6/JAkPM8agTBgLR66mrWa1Rf10s9dKBotDI=",
            BitgetClient.sign(preHash, fakeSecret)
        )
    }

    @Test
    fun bodyVectorMatchesToo() {
        val preHash = BitgetClient.preHash(
            fixedTimestamp,
            "POST",
            "/api/v2/mix/order/place-order",
            "",
            "{\"productType\":\"usdt-futures\",\"size\":\"1\"}"
        )
        assertEquals(
            "VvPUy5eibxuxYSKUtwN/9CipbU0KLvAsadDo14E7sEc=",
            BitgetClient.sign(preHash, fakeSecret)
        )
    }

    /**
     * Bitget secrets look like base64, and a client that decodes them first gets a different MAC —
     * which is the 40003 this app would otherwise ship with. The pinned vector below is what the
     * base64-decoded key produces; the shipped code deliberately does not use it.
     */
    @Test
    fun theSecretIsUsedAsRawUtf8BytesNotBase64Decoded() {
        val preHash = BitgetClient.preHash(
            fixedTimestamp, "GET", "/api/v2/spot/account/assets", "", ""
        )
        val decodedVariant = "9SHQA7ma+4XZJ8ImF4xcqYrpfqm3SgpQc9NIgO/Rr3o="
        assertEquals(decodedVariant, BitgetClient.signWithBase64DecodedKey(preHash, fakeSecret))
        assertNotEquals(decodedVariant, BitgetClient.sign(preHash, fakeSecret))
    }

    /** A secret that is not valid base64 at all must still sign the documented way. */
    @Test
    fun aNonBase64SecretStillSigns() {
        val preHash = BitgetClient.preHash(fixedTimestamp, "GET", "/api/v2/spot/account/assets", "", "")
        val signature = BitgetClient.sign(preHash, "not-base64-at-all-~^^")
        assertEquals(44, signature.length)
        assertTrue(signature.endsWith("="))
    }

    // ------------------------------------------------------------------ headers and query

    @Test
    fun timestampIsMillisecondsRenderedAsAString() {
        assertEquals("1700000000000", BitgetClient.timestampOf(1700000000000L))
        // The header and the signed value are the same string, or the server cannot recompute it.
        val stamp = BitgetClient.timestampOf(1700000000001L)
        assertEquals(stamp, BitgetClient.preHash(stamp, "GET", "/x", "", "").take(stamp.length))
    }

    @Test
    fun queryEncodingIsTheStringThatGetsSigned() {
        val query = BitgetClient.encodeQuery(linkedMapOf("productType" to "usdt-futures"))
        assertEquals("productType=usdt-futures", query)
        val preHash = BitgetClient.preHash(fixedTimestamp, "GET", "/api/v2/mix/account/accounts", query, "")
        assertEquals(
            "Tc0/oEiL6/JAkPM8agTBgLR66mrWa1Rf10s9dKBotDI=",
            BitgetClient.sign(preHash, fakeSecret)
        )
        assertEquals("", BitgetClient.encodeQuery(emptyMap()))
    }

    @Test
    fun pathsAreTheDocumentedV2Endpoints() {
        assertEquals("/api/v2/spot/account/assets", BitgetClient.SPOT_ASSETS_PATH)
        assertEquals("/api/v2/mix/account/accounts", BitgetClient.MIX_ACCOUNTS_PATH)
        assertEquals("GET", BitgetClient.METHOD)
        assertEquals("usdt-futures", BitgetClient.MIX_PRODUCT_TYPE)
    }

    // ------------------------------------------------------------------ reply parsing

    @Test
    fun successEnvelopeExposesTheDataArray() {
        val body = """
            {"code":"00000","msg":"success","requestTime":1700000000001,
             "data":[{"coin":"USDT","available":"12.5","frozen":"0","locked":"0","limitAvailable":"12.5","uTime":"1700000000000"},
                     {"coin":"BTC","available":"0.125","frozen":"0.5","locked":"0","limitAvailable":"0.125","uTime":"1700000000000"}]}
        """.trimIndent()
        val reply = BitgetClient.decodeEnvelope(body)
        assertTrue(reply.accepted)
        val assets = BitgetClient.spotAssets(reply.data)
        assertEquals(2, assets.size)
        assertEquals("USDT", assets[0].symbol)
        assertEquals(12.5, assets[0].available, 1e-9)
        assertEquals(0.0, assets[0].onHold, 1e-9)
        assertEquals("BTC", assets[1].symbol)
        assertEquals(0.625, assets[1].total, 1e-9)
        assertEquals("Spot", assets[1].account)
    }

    @Test
    fun futuresEquityComesFromAccountEquity() {
        val body = """
            {"code":"00000","msg":"success",
             "data":[{"marginCoin":"USDT","available":"90","locked":"0","accountEquity":"100.25",
                      "unrealizedPL":"1.25","isolatedMargin":"8","crossedMargin":"0"}]}
        """.trimIndent()
        val assets = BitgetClient.mixAssets(BitgetClient.decodeEnvelope(body).data)
        assertEquals(1, assets.size)
        assertEquals("USDT", assets[0].symbol)
        assertEquals(100.25, assets[0].available, 1e-9)
        assertEquals(8.0, assets[0].onHold, 1e-9)
        assertEquals("USDT futures", assets[0].account)
    }

    @Test
    fun errorEnvelopeIsNotAcceptedAndCarriesItsCode() {
        val reply = BitgetClient.decodeEnvelope("""{"code":"40003","msg":"Signature error","data":null}""")
        assertFalse(reply.accepted)
        assertEquals("40003", reply.code)
    }

    @Test
    fun anEmptyOrGarbageBodyIsRejectedNotCrashed() {
        listOf("", "   ", "not json", "[1,2]", "null").forEach { body ->
            val reply = BitgetClient.decodeEnvelope(body)
            assertFalse("accepted <$body>", reply.accepted)
        }
        assertTrue(BitgetClient.decodeEnvelope("""{"code":"00000","data":null}""").accepted)
        assertTrue(BitgetClient.spotAssets(null).isEmpty())
    }

    @Test
    fun aMalformedNumberFieldReadsAsZero() {
        val body = """{"code":"00000","data":[{"coin":"XUSDT","available":"n/a","frozen":"2"}]}"""
        val assets = BitgetClient.spotAssets(BitgetClient.decodeEnvelope(body).data)
        assertEquals(1, assets.size)
        assertEquals(0.0, assets[0].available, 1e-9)
        assertEquals(2.0, assets[0].onHold, 1e-9)
    }

    @Test
    fun aRowWithoutACoinNameIsDropped() {
        val body = """{"code":"00000","data":[{"available":"1"},{"coin":"ETH","available":"2"}]}"""
        assertEquals(1, BitgetClient.spotAssets(BitgetClient.decodeEnvelope(body).data).size)
        assertNull(BitgetClient.mixAssets(BitgetClient.decodeEnvelope(body).data).firstOrNull())
    }

    // ------------------------------------------------------------------ failure wording

    @Test
    fun eachDocumentedCodeBecomesItsOwnActionableKind() {
        val cases = mapOf(
            "40001" to BitgetFailureKind.INVALID_KEY,
            "40003" to BitgetFailureKind.SIGNATURE,
            "40006" to BitgetFailureKind.PASSPHRASE,
            "40009" to BitgetFailureKind.CLOCK_SKEW,
            "429" to BitgetFailureKind.RATE_LIMITED
        )
        cases.forEach { (code, kind) ->
            val failure = BitgetClient.failureOf(httpCode = 400, code = code)
            assertEquals(code, kind, failure.kind)
            assertTrue("$code produced an empty sentence", failure.message.isNotBlank())
            assertEquals(code, failure.code)
        }
    }

    /** The owner asked for the fix, not the number: each message names something to do. */
    @Test
    fun failureMessagesNameAFix() {
        assertTrue(BitgetClient.failureOf(400, "40001").message.contains("read-only", true))
        assertTrue(BitgetClient.failureOf(400, "40003").message.contains("Secret key", true))
        assertTrue(BitgetClient.failureOf(400, "40006").message.contains("passphrase", true))
        val skew = BitgetClient.failureOf(400, "40009")
        assertTrue(skew.message.contains("Date & time", true))
        assertTrue(skew.message.contains("30 seconds"))
        // Nothing is retried forever, so the wording tells the owner to act rather than wait.
        assertTrue(skew.message.contains("Nothing was retried", true))
    }

    @Test
    fun aClockSkewFailureIsNotReportedAsANetworkProblem() {
        assertEquals(BitgetFailureKind.CLOCK_SKEW, BitgetClient.failureOf(400, "40009").kind)
        assertEquals(BitgetFailureKind.NETWORK, BitgetClient.networkFailure(java.net.SocketTimeoutException()).kind)
    }

    /**
     * The standing rule: no message this client can produce may contain credential material, even
     * when the credentials are the fake ones above.
     */
    @Test
    fun noFailureMessageEverCarriesCredentialMaterial() {
        val bundle = BitgetSecretBundle(fakeApiKey, fakeSecret, fakePassphrase)
        val messages = buildList {
            add(BitgetClient.failureOf(401, "40001").message)
            add(BitgetClient.failureOf(400, "40003").message)
            add(BitgetClient.failureOf(400, "40006").message)
            add(BitgetClient.failureOf(400, "40009").message)
            add(BitgetClient.failureOf(429, "429").message)
            add(BitgetClient.failureOf(500, "").message)
            add(BitgetClient.networkFailure(RuntimeException("Unable to resolve host")).message)
        }
        messages.forEach { message ->
            assertFalse(message, message.contains(bundle.apiKey))
            assertFalse(message, message.contains(bundle.apiSecret))
            assertFalse(message, message.contains(bundle.passphrase))
        }
    }
}
