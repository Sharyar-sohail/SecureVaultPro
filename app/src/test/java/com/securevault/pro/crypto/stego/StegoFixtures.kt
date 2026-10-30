package com.securevault.pro.crypto.stego

import com.securevault.pro.presentation.screens.stego.StegoCodec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared, deterministic fixtures for the steganography tests.
 *
 * No Android type is touched: the engine takes and returns [LumaPlane], so every test here runs on
 * a bare JVM. Salts are fixed and passphrases turn into keys through PBKDF2 at a deliberately small
 * round count (the codec takes the count as a constructor argument) — the production 600 000 rounds
 * would make this suite minutes long without testing anything the extra rounds decide.
 */
internal object StegoFixtures {

    const val WIDTH = 640
    const val HEIGHT = 480

    /** Low enough to keep the suite fast, high enough to be a real PBKDF2 derivation. */
    const val ITERATIONS = 2_000

    /** Fixed carrier salt, so two seals of the same message are byte-identical. */
    val SALT = byteArrayOf(0x53, 0x56, 0x50, 0x01, 0x2A, 0x7C, 0x11, 0x5E)

    fun codec(): StegoCodec = StegoCodec(iterations = ITERATIONS)

    /**
     * A flat-ish texture: a slow horizontal gradient with two low-frequency ripples and a little
     * grain. The LL band is dominated by the gradient (spread ~30 grey levels), which is exactly the
     * "ordinary photo" the mark is meant to hide in.
     */
    fun gentleCarrier(): LumaPlane = carrier(grain = 5, seed = 0x51EE01)

    /** A noisy texture: the same gradient and ripples, plus heavy sensor grain. */
    fun noisyCarrier(): LumaPlane = carrier(grain = 26, seed = 0x51EE02)

    private fun carrier(grain: Int, seed: Int): LumaPlane {
        val random = DeterministicRandom(seed)
        val samples = IntArray(WIDTH * HEIGHT)
        val span = 2 * grain + 1
        for (y in 0 until HEIGHT) {
            val v = y.toDouble() / (HEIGHT - 1)
            for (x in 0 until WIDTH) {
                val u = x.toDouble() / (WIDTH - 1)
                val base = 74.0 + 104.0 * u
                val low = 18.0 * sin(2 * PI * 1.3 * u) + 12.0 * cos(2 * PI * 0.9 * v)
                val weave = 9.0 * sin(2 * PI * (2.0 * u + 3.0 * v))
                val delta = ((random.nextInt().toLong() and 0x7FFF_FFFFL) % span).toInt() - grain
                samples[y * WIDTH + x] = (base + low + weave + delta).roundToInt().coerceIn(0, 255)
            }
        }
        return LumaPlane(WIDTH, HEIGHT, samples)
    }

    fun envelope(
        secret: ByteArray,
        passphrase: String,
        kind: SecretKind = SecretKind.MESSAGE
    ): StegoEnvelopeRequest = StegoEnvelopeRequest(secret, kind, passphrase.toCharArray())

    fun seal(
        plane: LumaPlane,
        secret: ByteArray,
        passphrase: String,
        oneRead: Boolean = false
    ): StegoCodec.Sealed = codec().seal(
        StegoEncodeRequest(
            carrier = plane,
            envelopes = listOf(envelope(secret, passphrase)),
            oneRead = oneRead,
            salt = SALT
        )
    )

    fun scan(plane: LumaPlane, vararg passphrases: String): StegoCodec.Finding =
        codec().scan(StegoDecodeRequest(plane, passphrases.map { it.toCharArray() }))

    fun secret(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** A deterministic rectangular repaint, the cheapest "clone stamp" an editor can apply. */
    fun painted(plane: LumaPlane, left: Int, top: Int, width: Int, height: Int, value: Int): LumaPlane {
        val copy = plane.copy()
        for (y in top until (top + height).coerceAtMost(copy.height)) {
            for (x in left until (left + width).coerceAtMost(copy.width)) {
                copy.samples[y * copy.width + x] = value
            }
        }
        return copy
    }
}

/**
 * A documented stand-in for one black-and-white print / photocopy / scan round trip.
 *
 * Three transforms, applied in the order a real channel applies them:
 *
 *  1. **Global luminance stretch** — `v' = 128 + (v - 128) * contrast`. A copier re-exposes the
 *     page, so the tonal range moves and stretches; the engine is supposed to survive this by
 *     normalising the band to its own mean and spread.
 *  2. **Quantisation to `levels` evenly spaced grey levels** — 16 levels is a 4-bit scan, 32 levels
 *     a 5-bit one, 256 means no quantisation. A black-and-white print path collapses the page to far
 *     fewer tones than the camera captured.
 *  3. **Additive scanner noise** — uniform in `-noise..+noise` per sample, from a fixed seed. Loose
 *     paper, sensor noise and dithering all land as a few grey levels of static.
 *
 * Everything is deterministic: the noise generator is seeded and drawn in raster order.
 */
internal object PrintModel {

    fun press(
        plane: LumaPlane,
        levels: Int,
        contrast: Double = 1.0,
        noise: Int = 0,
        seed: Int = 0x0C0FFEE
    ): LumaPlane {
        require(levels in 2..256) { "A press quantises to 2..256 levels" }
        val step = 255.0 / (levels - 1)
        val random = DeterministicRandom(seed)
        val span = 2 * noise + 1
        return plane.map { value ->
            val stretched = 128.0 + (value - 128.0) * contrast
            val quantised = if (levels >= 256) stretched else (stretched / step).roundToInt() * step
            val jitter = if (noise <= 0) 0
            else ((random.nextInt().toLong() and 0x7FFF_FFFFL) % span).toInt() - noise
            quantised.roundToInt() + jitter
        }
    }
}
