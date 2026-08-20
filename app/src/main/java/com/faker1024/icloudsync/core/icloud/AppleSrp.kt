package com.faker1024.icloudsync.core.icloud

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Apple web authentication's RFC 5054 SRP-6a proof calculation. */
internal class AppleSrp(
    private val secret: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
) {
    private val a = BigInteger(1, secret)
    private val publicA = G.modPow(a, N)

    fun publicValue(): ByteArray = publicA.toFixedWidth()

    fun proofs(
        accountName: String,
        password: String,
        salt: ByteArray,
        iterations: Int,
        protocol: String,
        serverPublicValue: ByteArray,
    ): SrpProofs {
        require(iterations > 0) { "SRP 迭代次数无效" }
        val serverB = BigInteger(1, serverPublicValue)
        require(serverB > BigInteger.ZERO && serverB < N) { "SRP 服务端公钥无效" }

        val derivedPassword = derivePassword(password, salt, iterations, protocol)
        val x = BigInteger(1, sha256(salt, sha256(byteArrayOf(':'.code.toByte()), derivedPassword)))
        val u = BigInteger(1, sha256(publicA.toFixedWidth(), serverB.toFixedWidth()))
        require(u != BigInteger.ZERO) { "SRP 随机参数无效" }

        val multiplier = BigInteger(1, sha256(N.toUnsignedBytes(), G.toFixedWidth()))
        val verifier = G.modPow(x, N)
        val rawBase = serverB.subtract(multiplier.multiply(verifier)).remainder(N)
        val base = if (rawBase.signum() < 0) rawBase.add(N) else rawBase
        val sharedSecret = base.modPow(a.add(u.multiply(x)), N).toFixedWidth()
        val sessionKey = sha256(sharedSecret)
        val aBytes = publicA.toFixedWidth()
        val bBytes = serverB.toFixedWidth()

        val groupHash = sha256(G.toFixedWidth())
        val modulusHash = sha256(N.toUnsignedBytes())
        val xorHash = ByteArray(groupHash.size) { groupHash[it].toInt().xor(modulusHash[it].toInt()).toByte() }
        val accountHash = sha256(accountName.lowercase(Locale.US).toByteArray(Charsets.UTF_8))
        val m1 = sha256(xorHash, accountHash, salt, aBytes, bBytes, sessionKey)
        val m2 = sha256(aBytes, m1, sessionKey)

        derivedPassword.fill(0)
        sharedSecret.fill(0)
        sessionKey.fill(0)
        return SrpProofs(m1, m2)
    }

    companion object {
        internal fun derivePassword(
            password: String,
            salt: ByteArray,
            iterations: Int,
            protocol: String,
        ): ByteArray {
            val passwordHash = sha256(password.toByteArray(Charsets.UTF_8))
            val material = when (protocol) {
                "s2k" -> passwordHash.copyOf()
                "s2k_fo" -> passwordHash.joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                }.toByteArray(Charsets.US_ASCII)
                else -> throw IllegalArgumentException("不支持的 SRP 协议：$protocol")
            }
            return try {
                pbkdf2HmacSha256(material, salt, iterations, 32)
            } finally {
                passwordHash.fill(0)
                material.fill(0)
            }
        }

        private fun pbkdf2HmacSha256(
            password: ByteArray,
            salt: ByteArray,
            iterations: Int,
            outputLength: Int,
        ): ByteArray {
            require(iterations > 0)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val hashLength = mac.macLength
            val output = ByteArray(outputLength)
            var outputOffset = 0
            var blockIndex = 1
            while (outputOffset < outputLength) {
                val blockSuffix = ByteBuffer.allocate(4).putInt(blockIndex).array()
                var u = mac.doFinal(salt + blockSuffix)
                val block = u.copyOf()
                for (round in 1 until iterations) {
                    u = mac.doFinal(u)
                    for (index in block.indices) block[index] = block[index].toInt().xor(u[index].toInt()).toByte()
                }
                val count = minOf(hashLength, outputLength - outputOffset)
                block.copyInto(output, outputOffset, 0, count)
                u.fill(0)
                block.fill(0)
                outputOffset += count
                blockIndex++
            }
            return output
        }

        private fun sha256(vararg values: ByteArray): ByteArray = MessageDigest
            .getInstance("SHA-256")
            .apply { values.forEach(::update) }
            .digest()

        private fun BigInteger.toUnsignedBytes(): ByteArray {
            val bytes = toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        private fun BigInteger.toFixedWidth(): ByteArray {
            val bytes = toUnsignedBytes()
            require(bytes.size <= SRP_WIDTH) { "SRP 数值长度无效" }
            return ByteArray(SRP_WIDTH).also { bytes.copyInto(it, SRP_WIDTH - bytes.size) }
        }

        private const val SRP_WIDTH = 256
        private val G = BigInteger.valueOf(2)
        private val N = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
                "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
                "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
                "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
                "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
                "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
                "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
                "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
            16,
        )
    }
}

internal data class SrpProofs(val m1: ByteArray, val m2: ByteArray)
