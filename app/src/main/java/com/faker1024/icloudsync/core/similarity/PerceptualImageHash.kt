package com.faker1024.icloudsync.core.similarity

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Small, platform-independent descriptors; never retain decoded image pixels. */
internal data class ImageFingerprint(
    val perceptualHash: Long,
    val differenceHash: Long,
    val aspectRatio: Double,
    val colorGrid: IntArray,
    val contrast: Double,
)

/** Independent implementation of standard DCT pHash and horizontal dHash. */
internal object PerceptualImageHash {
    private const val SAMPLE_SIZE = 32
    private const val LOW_FREQUENCIES = 8
    private val basis = Array(LOW_FREQUENCIES) { frequency ->
        DoubleArray(SAMPLE_SIZE) { position ->
            cos(Math.PI * (2 * position + 1) * frequency / (2 * SAMPLE_SIZE))
        }
    }

    fun fingerprint(
        pixels: IntArray,
        width: Int,
        height: Int,
        aspectRatio: Double = width.toDouble() / height,
    ): ImageFingerprint {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(aspectRatio.isFinite() && aspectRatio > 0)
        val grey = sample(pixels, width, height, SAMPLE_SIZE, SAMPLE_SIZE).map(::luminance)
        val mean = grey.average()
        val contrast = sqrt(grey.sumOf { (it - mean) * (it - mean) } / grey.size)
        val rowDct = Array(SAMPLE_SIZE) { y ->
            DoubleArray(LOW_FREQUENCIES) { u ->
                (0 until SAMPLE_SIZE).sumOf { x -> grey[y * SAMPLE_SIZE + x] * basis[u][x] }
            }
        }
        val low = DoubleArray(LOW_FREQUENCIES * LOW_FREQUENCIES - 1)
        var index = 0
        for (v in 0 until LOW_FREQUENCIES) {
            for (u in 0 until LOW_FREQUENCIES) {
                if (u == 0 && v == 0) continue // The DC component is brightness, not structure.
                val coefficient = (0 until SAMPLE_SIZE).sumOf { y -> rowDct[y][u] * basis[v][y] }
                // Suppress floating-point noise in blank / nearly constant images.
                low[index++] = if (abs(coefficient) < 0.000001) 0.0 else coefficient
            }
        }
        val median = low.sorted()[low.size / 2]
        var perceptualHash = 0L
        low.forEachIndexed { bit, value ->
            if (value > median) perceptualHash = perceptualHash or (1L shl bit)
        }
        val differences = sample(pixels, width, height, 9, 8).map(::luminance)
        var differenceHash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (differences[y * 9 + x] > differences[y * 9 + x + 1]) {
                    differenceHash = differenceHash or (1L shl (y * 8 + x))
                }
            }
        }
        // Local color averages distinguish otherwise identical hash patterns (e.g. solid colors).
        val colors = IntArray(4 * 4 * 3)
        val colorSamples = sample(pixels, width, height, SAMPLE_SIZE, SAMPLE_SIZE)
        for (gy in 0 until 4) {
            for (gx in 0 until 4) {
                for (channel in 0 until 3) {
                    var total = 0
                    for (y in gy * 8 until (gy + 1) * 8) {
                        for (x in gx * 8 until (gx + 1) * 8) {
                            total += (colorSamples[y * SAMPLE_SIZE + x] ushr (16 - 8 * channel)) and 255
                        }
                    }
                    colors[(gy * 4 + gx) * 3 + channel] = total / 64
                }
            }
        }
        return ImageFingerprint(perceptualHash, differenceHash, aspectRatio, colors, contrast)
    }

    private fun luminance(argb: Int): Double =
        ((argb ushr 16) and 255) * 0.299 + ((argb ushr 8) and 255) * 0.587 +
            (argb and 255) * 0.114

    /** Bilinear sampling, alpha composited over white so invisible RGB does not affect matches. */
    private fun sample(pixels: IntArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): IntArray =
        IntArray(targetWidth * targetHeight) { index ->
            val sx = ((index % targetWidth + 0.5) * width / targetWidth - 0.5).coerceIn(0.0, (width - 1).toDouble())
            val sy = ((index / targetWidth + 0.5) * height / targetHeight - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val x = sx.toInt()
            val y = sy.toInt()
            val nextX = (x + 1).coerceAtMost(width - 1)
            val nextY = (y + 1).coerceAtMost(height - 1)
            val fx = sx - x
            val fy = sy - y
            var result = -0x1000000
            for (shift in listOf(16, 8, 0)) {
                fun channel(at: Int): Double {
                    val pixel = pixels[at]
                    val alpha = (pixel ushr 24) / 255.0
                    return ((pixel ushr shift) and 255) * alpha + 255 * (1 - alpha)
                }
                val upper = channel(y * width + x) * (1 - fx) + channel(y * width + nextX) * fx
                val lower = channel(nextY * width + x) * (1 - fx) + channel(nextY * width + nextX) * fx
                result = result or (((upper * (1 - fy) + lower * fy).roundToInt().coerceIn(0, 255)) shl shift)
            }
            result
        }
}

internal data class SimilarityThresholds(
    val perceptualDistance: Int,
    val differenceDistance: Int,
    val colorDistance: Double,
    val aspectDifference: Double,
) {
    companion object {
        fun forLevel(level: SimilarityLevel): SimilarityThresholds = when (level) {
            SimilarityLevel.STRICT -> SimilarityThresholds(4, 6, 14.0, 0.025)
            SimilarityLevel.BALANCED -> SimilarityThresholds(7, 10, 24.0, 0.05)
            SimilarityLevel.LOOSE -> SimilarityThresholds(10, 14, 34.0, 0.10)
        }
    }
}

internal fun similarityScore(left: ImageFingerprint, right: ImageFingerprint, thresholds: SimilarityThresholds): Int? {
    val aspectDifference = abs(left.aspectRatio - right.aspectRatio) / max(left.aspectRatio, right.aspectRatio)
    if (aspectDifference > thresholds.aspectDifference) return null
    val perceptualDistance = java.lang.Long.bitCount(left.perceptualHash xor right.perceptualHash)
    if (perceptualDistance > thresholds.perceptualDistance) return null
    val differenceDistance = java.lang.Long.bitCount(left.differenceHash xor right.differenceHash)
    if (differenceDistance > thresholds.differenceDistance) return null
    val colorDistance = left.colorGrid.indices.sumOf { abs(left.colorGrid[it] - right.colorGrid[it]) }.toDouble() /
        left.colorGrid.size
    if (colorDistance > thresholds.colorDistance) return null
    // Low-detail images do not have enough structure for perceptual hashes to be reliable.
    if (minOf(left.contrast, right.contrast) < 5.0 && (colorDistance > 3.0 || abs(left.contrast - right.contrast) > 2.0)) {
        return null
    }
    val penalty = (perceptualDistance / 63.0 * 0.45 + differenceDistance / 64.0 * 0.30 +
        colorDistance / 255.0 * 0.25) * 100
    return (100 - penalty.roundToInt()).coerceIn(0, 100)
}

internal data class FingerprintGroup<T>(val members: MutableList<Pair<T, ImageFingerprint>>, var score: Int = 100)

/** Complete-link matching: every pair in a group passes all safeguards. No A→B→C chains. */
internal class SimilarityGrouper<T>(level: SimilarityLevel) {
    private val thresholds = SimilarityThresholds.forLevel(level)
    private val groups = mutableListOf<FingerprintGroup<T>>()
    private var root: HashNode? = null

    fun add(item: T, fingerprint: ImageFingerprint, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        val candidates = candidateGroups(fingerprint.perceptualHash, checkCancelled)
        for (candidate in candidates.sorted()) {
            val group = groups[candidate]
            var weakestScore = group.score
            var matches = true
            for ((index, member) in group.members.withIndex()) {
                if (index % 32 == 0) checkCancelled()
                val score = similarityScore(member.second, fingerprint, thresholds)
                if (score == null) {
                    matches = false
                    break
                }
                weakestScore = minOf(weakestScore, score)
            }
            if (matches) {
                group.members.add(item to fingerprint)
                group.score = weakestScore
                return
            }
        }
        val groupIndex = groups.size
        groups.add(FingerprintGroup(mutableListOf(item to fingerprint)))
        indexRepresentative(fingerprint.perceptualHash, groupIndex)
    }

    fun result(): List<FingerprintGroup<T>> = groups.filter { it.members.size > 1 }

    // BK-tree uses the Hamming metric to avoid comparing every pair of unrelated photographs.
    private fun candidateGroups(hash: Long, checkCancelled: () -> Unit): List<Int> {
        val pending = ArrayDeque<HashNode>()
        root?.let(pending::add)
        val result = mutableListOf<Int>()
        var visited = 0
        while (pending.isNotEmpty()) {
            if (visited++ % 32 == 0) checkCancelled()
            val node = pending.removeLast()
            val distance = java.lang.Long.bitCount(node.hash xor hash)
            if (distance <= thresholds.perceptualDistance) result.addAll(node.groupIndices)
            val lower = distance - thresholds.perceptualDistance
            val upper = distance + thresholds.perceptualDistance
            node.children.forEach { (edge, child) -> if (edge in lower..upper) pending.add(child) }
        }
        return result
    }

    private fun indexRepresentative(hash: Long, group: Int) {
        var node = root ?: HashNode(hash).also { root = it }
        while (true) {
            val distance = java.lang.Long.bitCount(node.hash xor hash)
            if (distance == 0) {
                node.groupIndices.add(group)
                return
            }
            val child = node.children[distance]
            if (child == null) {
                node.children[distance] = HashNode(hash).also { it.groupIndices.add(group) }
                return
            }
            node = child
        }
    }

    private class HashNode(val hash: Long) {
        val groupIndices = mutableListOf<Int>()
        val children = mutableMapOf<Int, HashNode>()
    }
}
