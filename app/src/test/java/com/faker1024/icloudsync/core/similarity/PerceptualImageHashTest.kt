package com.faker1024.icloudsync.core.similarity

import java.util.Random
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualImageHashTest {
    @Test
    fun identicalImagesHavePerfectAgreement() {
        val pixels = photographPattern()
        val left = hash(pixels)
        val right = hash(pixels.copyOf())
        assertEquals(100, similarityScore(left, right, strict))
    }

    @Test
    fun resizedCopyMatchesOriginal() {
        val original = photographPattern()
        val enlarged = IntArray(128 * 128) { index -> original[(index / 128 / 2) * 64 + index % 128 / 2] }
        val resizedHash = PerceptualImageHash.fingerprint(enlarged, 128, 128)
        assertEquals(100, similarityScore(hash(original), resizedHash, strict))
    }

    @Test
    fun smallBrightnessChangeRemainsSimilar() {
        val original = photographPattern()
        val brighter = original.map { pixel ->
            rgb(((pixel ushr 16) and 255) + 5, ((pixel ushr 8) and 255) + 5, (pixel and 255) + 5)
        }.toIntArray()
        val score = similarityScore(hash(original), hash(brighter), strict)
        assertNotNull(score)
        assertTrue(score!! >= 95)
    }

    @Test
    fun mildCompressionLikeNoiseRemainsSimilar() {
        val random = Random(91)
        val original = photographPattern()
        val noisy = original.map { pixel ->
            val delta = random.nextInt(5) - 2
            rgb(((pixel ushr 16) and 255) + delta, ((pixel ushr 8) and 255) + delta, (pixel and 255) + delta)
        }.toIntArray()
        assertNotNull(similarityScore(hash(original), hash(noisy), balanced))
    }

    @Test
    fun unrelatedSpatialPatternsAreRejected() {
        val random = Random(771)
        val noise = IntArray(64 * 64) { rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        assertNull(similarityScore(hash(photographPattern()), hash(noise), loose))
    }

    @Test
    fun solidColorsAreNotDuplicatesDespiteEqualStructuralHashes() {
        val red = hash(IntArray(64 * 64) { rgb(255, 0, 0) })
        val blue = hash(IntArray(64 * 64) { rgb(0, 0, 255) })
        assertEquals(red.perceptualHash, blue.perceptualHash)
        assertEquals(red.differenceHash, blue.differenceHash)
        assertNull(similarityScore(red, blue, loose))
    }

    @Test
    fun lowDetailImagesRequireTightColorAgreement() {
        val grey = hash(IntArray(64 * 64) { rgb(100, 100, 100) })
        val lighter = hash(IntArray(64 * 64) { rgb(112, 112, 112) })
        assertNull(similarityScore(grey, lighter, loose))
    }

    @Test
    fun aspectRatioGateRejectsStretchedCopies() {
        val original = hash(photographPattern())
        assertNull(similarityScore(original, original.copy(aspectRatio = 1.7), loose))
    }

    @Test
    fun transparentRgbDoesNotAffectVisibleContent() {
        val invisibleRed = hash(IntArray(64 * 64) { 0x00ff0000 })
        val invisibleBlue = hash(IntArray(64 * 64) { 0x000000ff })
        assertEquals(100, similarityScore(invisibleRed, invisibleBlue, strict))
    }

    @Test
    fun groupingRejectsTransitiveChainsEvenWhenBothMatchRepresentative() {
        val base = fakeHash(0L)
        val first = base.copy(perceptualHash = 0b000000001111L)
        val second = base.copy(perceptualHash = 0b111100000000L)
        assertNotNull(similarityScore(base, first, strict))
        assertNotNull(similarityScore(base, second, strict))
        assertNull(similarityScore(first, second, strict))
        val groups = SimilarityGrouper<String>(SimilarityLevel.STRICT)
        groups.add("base", base)
        groups.add("first", first)
        groups.add("second", second)
        assertEquals(1, groups.result().size)
        assertEquals(listOf("base", "first"), groups.result().single().members.map { it.first })
    }

    @Test
    fun groupScoreUsesWeakestPair() {
        val base = fakeHash(0L)
        val other = base.copy(perceptualHash = 0b11L)
        val group = SimilarityGrouper<String>(SimilarityLevel.STRICT)
        group.add("one", base)
        group.add("two", other)
        group.add("three", base)
        assertEquals(similarityScore(base, other, strict), group.result().single().score)
    }

    @Test
    fun indexFindsGroupsBeyondTheRoot() {
        val groups = SimilarityGrouper<String>(SimilarityLevel.STRICT)
        groups.add("unrelated", fakeHash(0x7fffffffL))
        groups.add("pair-a", fakeHash(0L))
        groups.add("pair-b", fakeHash(1L))
        assertEquals(listOf("pair-a", "pair-b"), groups.result().single().members.map { it.first })
    }

    @Test
    fun identicalStructuralHashesStillFormSeparateColorGroups() {
        val groups = SimilarityGrouper<String>(SimilarityLevel.STRICT)
        val dark = fakeHash(0L)
        val light = dark.copy(colorGrid = IntArray(48) { 200 })
        groups.add("dark-a", dark)
        groups.add("light-a", light)
        groups.add("light-b", light)
        assertEquals(listOf("light-a", "light-b"), groups.result().single().members.map { it.first })
    }

    @Test
    fun sensitivityLevelsActuallyChangeCandidateAcceptance() {
        val base = fakeHash(0L)
        val altered = base.copy(perceptualHash = 0b111111L)
        assertNull(similarityScore(base, altered, strict))
        assertNotNull(similarityScore(base, altered, balanced))
    }

    @Test
    fun cancellationIsPropagatedWithoutAddingFile() {
        val groups = SimilarityGrouper<String>(SimilarityLevel.STRICT)
        groups.add("first", fakeHash(0L))
        var checks = 0
        try {
            groups.add("cancelled", fakeHash(0L)) {
                if (++checks == 3) throw CancellationException("cancel scan")
            }
            throw AssertionError("Expected cancellation during group comparison")
        } catch (_: CancellationException) {
            assertTrue(groups.result().isEmpty())
        }
    }

    private fun hash(pixels: IntArray): ImageFingerprint = PerceptualImageHash.fingerprint(pixels, 64, 64)

    private fun fakeHash(value: Long): ImageFingerprint = ImageFingerprint(
        perceptualHash = value,
        differenceHash = 0,
        aspectRatio = 1.0,
        colorGrid = IntArray(48) { 100 },
        contrast = 40.0,
    )

    private fun photographPattern(): IntArray = IntArray(64 * 64) { index ->
        val x = index % 64
        val y = index / 64
        // Smooth gradients, an off-centre foreground shape and non-symmetric detail.
        val shape = if ((x - 22) * (x - 22) + (y - 34) * (y - 34) < 180) 55 else 0
        rgb(35 + x * 2 + shape, 30 + y * 2 + shape / 2, 45 + (x + y) / 2 + (x * y % 17))
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = -0x1000000 or
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private val strict = SimilarityThresholds.forLevel(SimilarityLevel.STRICT)
    private val balanced = SimilarityThresholds.forLevel(SimilarityLevel.BALANCED)
    private val loose = SimilarityThresholds.forLevel(SimilarityLevel.LOOSE)
}
