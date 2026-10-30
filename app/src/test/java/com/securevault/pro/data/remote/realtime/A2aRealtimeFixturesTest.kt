package com.securevault.pro.data.remote.realtime

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.securevault.pro.data.remote.firebase.KeyringCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The fixture guard (A2a). `sync_engine/fixtures/<module>/` is what agent A3's JavaScript parser
 * and merge are tested against without a phone, so these files must be exactly what this code
 * produces — not a hand-written approximation that can rot.
 *
 * Every test here reads the checked-in files and compares them against the live codec, the node
 * parser and the merge resolver. Change the contract without changing a fixture and this suite goes
 * red, which is the point.
 */
class A2aRealtimeFixturesTest {

    private val codec = RealtimeRecordCodec()
    private val modules = RealtimeModule.values().toList()

    @Test
    fun everyModuleShipsAllThreeFixtures() {
        assertEquals(7, modules.size)
        val directories = A2aRealtimeSamples.fixturesRoot().listFiles()!!.filter { it.isDirectory }
        assertEquals(modules.map { it.segment }.sorted(), directories.map { it.name }.sorted())
        for (module in modules) {
            for (name in listOf("record.json", "envelope.json", "vectors.json")) {
                val file = A2aRealtimeSamples.fixture(module, name)
                assertTrue("${module.segment}/$name missing", file.isFile)
                assertTrue("${module.segment}/$name is empty", file.readText().isNotBlank())
            }
        }
    }

    @Test
    fun recordFixturesMatchTheCodecsOwnOutput() {
        for ((module, entity) in A2aRealtimeSamples.all()) {
            val checkedIn = A2aRealtimeSamples.readFixture(module, "record.json")
            val tree = RealtimeFieldSchema.parseObject(checkedIn)
            assertNotNull("${module.segment}/record.json is not strict JSON", tree)
            assertEquals(
                "${module.segment}: fixture keys drifted from the published table",
                RealtimeFieldSchema.keysOf(module),
                tree!!.keySet().toList()
            )
            val decoded = codec.decodeRecord(module, checkedIn)
            assertNotNull("${module.segment}/record.json does not decode", decoded)
            assertEquals("${module.segment} fixture value drifted", entity, decoded!!.entity)
            assertEquals(
                "${module.segment} re-encode drifted",
                canonical(codec.encode(module, entity)),
                canonical(checkedIn)
            )
            assertEquals(module, codec.decodeAny(checkedIn)?.module)
        }
    }

    @Test
    fun envelopeFixturesAreValidCiphertextOnlyNodes() {
        for (module in modules) {
            val text = A2aRealtimeSamples.readFixture(module, "envelope.json")
            val tree = RealtimeFieldSchema.parseObject(text)
            assertNotNull("${module.segment}/envelope.json", tree)
            assertEquals(RealtimeEnvelope.FIELDS.sorted(), tree!!.keySet().toList().sorted())
            val node = RealtimeEnvelope.fromCloudMap(nodeMap(tree))
            assertNotNull("${module.segment}/envelope.json is not a valid node", node)
            assertEquals(A2aRealtimeSamples.FAKE_SEAL, node!!.e)
            assertEquals(A2aRealtimeSamples.clockOf(module), node.u)
            assertEquals(A2aRealtimeSamples.revisionOf(module), node.r)
            assertNull(node.d)
            assertFalse(node.isTombstone())
            // Ciphertext-only: the record's own id, a plain string in record.json, must not appear.
            val recordId = RealtimeFieldSchema.parseObject(
                A2aRealtimeSamples.readFixture(module, "record.json")
            )!!.get("id").asString
            assertFalse("${module.segment}/envelope.json leaks the record id", text.contains(recordId))
        }
    }

    @Test
    fun vectorFixturesReproduceTheMergeTable() {
        for (module in modules) {
            val root = RealtimeFieldSchema.parseObject(
                A2aRealtimeSamples.readFixture(module, "vectors.json")
            )!!
            assertEquals(module.segment, root.get("module").asString)
            assertEquals(A2aRealtimeSamples.BASE, root.get("clockBase").asLong)
            assertEquals(RealtimeEnvelope.FIELDS.sorted(), root.getAsJsonArray("nodeFields").map { it.asString }.sorted())
            assertEquals(
                RealtimeFieldSchema.keysOf(module),
                root.getAsJsonArray("recordKeys").map { it.asString }
            )
            val cases: JsonArray = root.getAsJsonArray("cases")
            assertTrue("$module has no vectors", cases.size() >= 10)
            for (element in cases) {
                assertCase(module, element.asJsonObject)
            }
        }
    }

    private fun assertCase(module: RealtimeModule, testCase: JsonObject) {
        val name = "${module.segment}/${testCase.get("name").asString}"
        val local = nodeMap(testCase.get("local"))
        val incoming = nodeMap(testCase.get("incoming"))
        assertNotNull("$name needs an incoming node", incoming)
        val expected = testCase.getAsJsonObject("expected")
        val parsed = RealtimeEnvelope.fromCloudMap(incoming)
        assertEquals("$name nodeAccepted", expected.get("nodeAccepted").asBoolean, parsed != null)
        val result = RealtimeMergeResolver.resolveNodes(local, incoming)
        assertEquals("$name decision", expected.get("decision").asString, result.decision.name)
        assertEquals(
            "$name outcome",
            if (expected.get("outcome").isJsonNull) null else expected.get("outcome").asString,
            result.outcome?.name
        )
        assertEquals("$name applyRemote", expected.get("applyRemote").asBoolean, result.applyRemote)
        assertEquals("$name remoteDropped", expected.get("remoteDropped").asBoolean, result.remoteDropped)
        assertEquals(
            "$name deleteWins",
            expected.get("deleteWins").asBoolean,
            (result.localD ?: result.remoteD) != null
        )
        if (parsed != null) {
            assertEquals(incoming!![RealtimeEnvelope.FIELD_U], result.remoteU)
            assertEquals(incoming!![RealtimeEnvelope.FIELD_R], result.remoteR)
        }
    }

    @Test
    fun fixturesCarryNoRealSecrets() {
        val markers = listOf("sample", "synthetic", "fix-", "placeholder-not-a-real-seal", "not-a-real")
        for (module in modules) {
            for (name in listOf("record.json", "envelope.json", "vectors.json")) {
                val text = A2aRealtimeSamples.readFixture(module, name)
                val lower = text.lowercase()
                assertTrue("$module/$name looks like it holds real data", markers.any { lower.contains(it) })
                assertFalse("$module/$name mentions the dead universal key", text.contains("PRIMARY_CLOUD_KEY"))
            }
        }
        // The seal in a fixture is a literal marker with a fixed key-sized probe, never something
        // that authenticates: a real seal would mean a real key was used to make the file.
        val probe = ByteArray(KeyringCodec.KEY_BYTES) { (it % 7).toByte() }
        assertNull(KeyringCodec.openBytes(A2aRealtimeSamples.FAKE_SEAL, probe))
    }

    /**
     * A fixture node object converted the way the database hands one over: whole numbers as
     * `Long`, fractional as `Double`, text as `String`, JSON null as null.
     */
    private fun nodeMap(element: JsonElement?): Map<String, Any?>? {
        if (element == null || element.isJsonNull) return null
        val tree = element.asJsonObject
        val values = LinkedHashMap<String, Any?>(tree.size())
        // `JsonObject` is not a `java.util.Map`, so it has no destructuring iterator: walk the keys.
        for (key in tree.keySet()) {
            val value = tree.get(key)
            values[key] = when {
                value.isJsonNull -> null
                !value.isJsonPrimitive -> value
                value.asJsonPrimitive.isBoolean -> value.asBoolean
                value.asJsonPrimitive.isNumber -> {
                    val raw = BigDecimal(value.asJsonPrimitive.asString).stripTrailingZeros()
                    if (raw.scale() <= 0) raw.longValueExact() else value.asDouble
                }
                else -> value.asString
            }
        }
        return values
    }

    /** Order- and number-format-insensitive JSON text, so two encodings of one record compare equal. */
    private fun canonical(json: String): String = render(RealtimeFieldSchema.parseObject(json))

    private fun render(element: JsonElement?): String = when {
        element == null || element.isJsonNull -> "null"
        element is JsonObject -> element.keySet().sorted().joinToString(",", "{", "}") {
            "\"$it\":${render(element.get(it))}"
        }
        element is JsonArray -> element.joinToString(",", "[", "]") { render(it) }
        element.isJsonPrimitive -> renderPrimitive(element.asJsonPrimitive)
        else -> "unexpected $element"
    }

    private fun renderPrimitive(primitive: JsonPrimitive): String = when {
        primitive.isBoolean -> primitive.asBoolean.toString()
        primitive.isNumber -> BigDecimal(primitive.asString).stripTrailingZeros().toPlainString()
        else -> "\"" + primitive.asString + "\""
    }
}
