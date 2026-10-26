package tk.glucodata.drivers.ottai

import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/** GBL v3 envelope validation, not a replacement for the sensor's signature verification. */
internal class OttaiOtaPackage private constructor(
    private val content: ByteArray,
    val sha256: String,
) {
    val size: Int get() = content.size
    fun chunk(offset: Int, size: Int): ByteArray = content.copyOfRange(offset, minOf(offset + size, content.size))
    fun bytes(): ByteArray = content.copyOf()

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        private const val HEADER = 0x03A617EBL
        private const val END = 0xFC0404FCL
        private val tags = setOf(HEADER, END, 0xF40A0AF4L, 0xF50909F5L, 0xFE0101FEL,
            0xF60808F6L, 0xF70A0AF7L, 0xF30B0BF3L, 0xFB0505FBL,
            0x5EA617EBL, 0x76A617EBL)

        fun parse(bytes: ByteArray): OttaiOtaPackage {
            require(bytes.size in 28..MAX_BYTES) { "gbl_size" }
            fun u32(at: Int): Long = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            require(u32(0) == HEADER && u32(4) == 8L && u32(8) ushr 24 == 3L) { "gbl_header" }
            var at = 0
            var end = -1
            while (at < bytes.size) {
                require(bytes.size - at >= 8) { "gbl_truncated" }
                val tag = u32(at)
                val length = u32(at + 4)
                require(tag in tags || tag in setOf(0xFA0606FAL, 0xF90707F9L, 0xFD0303FDL,
                    0xFD0505FDL, 0xFD0707FDL)) { "gbl_tag" }
                require(at == 0 || tag != HEADER) { "gbl_header_duplicate" }
                require(length <= bytes.size.toLong() - at - 8) { "gbl_length" }
                at += 8 + length.toInt()
                if (tag == END) {
                    require(length == 4L) { "gbl_end" }
                    end = at
                    break
                }
            }
            require(end > 0 && bytes.copyOfRange(end, bytes.size).all { it == 0xff.toByte() }) { "gbl_end" }
            require(CRC32().apply { update(bytes, 0, end) }.value == 0x2144df1cL) { "gbl_crc" }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            return OttaiOtaPackage(bytes.copyOf(), digest)
        }
    }
}

internal object OttaiOtaProtocol {
    // Recovered CN 1.44 sequence in PR #727, deliberately not the standard SiLabs 0x03 finish.
    const val FINISH: Byte = 0x06
    const val WRITE_DELAY_MS = 60L
    private val versionPattern = Regex("V1\\.([3-8])\\.\\d+(?:\\.\\d+)*")
    fun version(value: String): String? = value.trim().takeIf { versionPattern.matches(it) }
    fun minor(version: String): Int = versionPattern.matchEntire(version)?.groupValues?.get(1)?.toInt() ?: -1

    fun isUpgrade(source: String, target: String): Boolean {
        require(version(source) != null && version(target) != null) { "target_version" }
        val from = source.drop(1).split('.').map { it.toIntOrNull() ?: throw IllegalArgumentException("target_version") }
        val to = target.drop(1).split('.').map { it.toIntOrNull() ?: throw IllegalArgumentException("target_version") }
        for (index in 0 until maxOf(from.size, to.size)) {
            val comparison = (to.getOrNull(index) ?: 0).compareTo(from.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    fun downloadUri(value: String): URI {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "download_url" }
        return uri
    }

    fun targetVersion(url: String): String {
        val file = downloadUri(url).path.substringAfterLast('/')
        require(file.endsWith(".gbl", ignoreCase = true)) { "download_filename" }
        return version(file.dropLast(4)) ?: throw IllegalArgumentException("target_version")
    }

    fun chunkSize(mtu: Int): Int {
        require(mtu >= 23) { "mtu" }
        return (minOf(244, mtu - 3) / 4) * 4
    }

    fun coefficientPacket(csv: String): ByteArray {
        val values = csv.split(',').map { it.trim().toFloatOrNull() ?: throw IllegalArgumentException("coefficient") }
        require(values.size in 1..255 && values.all { it.isFinite() }) { "coefficient" }
        val packet = ByteBuffer.allocate(2 + values.size * 4 + 2).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x09.toByte()).put(values.size.toByte())
        values.forEach { packet.putFloat(it) }
        var crc = 0xffff
        packet.array().take(packet.position()).forEach { byte ->
            crc = crc xor (byte.toInt() and 255)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1 }
        }
        return packet.putShort(crc.toShort()).array()
    }

    fun calibrationPacket(k: Double?, b: Double?, session: String): ByteArray {
        require(k != null && b != null && k.isFinite() && b.isFinite()) { "calibration_missing" }
        val plain = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putDouble(k).putDouble(b).array()
        val encrypted = OttaiCrypto.encryptPayload(plain, session) ?: throw IllegalArgumentException("calibration_encrypt")
        require(encrypted.size == 16) { "calibration_length" }
        return byteArrayOf(0x0b) + encrypted
    }
}

/** Acknowledged offsets only. A timeout/disconnect never retries an uncertain chunk. */
internal class OttaiOtaTransfer(val image: OttaiOtaPackage, mtu: Int) {
    private val chunkSize = OttaiOtaProtocol.chunkSize(mtu)
    var acknowledgedBytes = 0
        private set
    private var pendingBytes = 0
    fun nextChunk(): ByteArray {
        check(pendingBytes == 0 && acknowledgedBytes < image.size)
        return image.chunk(acknowledgedBytes, chunkSize).also { pendingBytes = it.size }
    }
    fun acknowledge() {
        check(pendingBytes > 0)
        acknowledgedBytes += pendingBytes
        pendingBytes = 0
    }
    val complete: Boolean get() = acknowledgedBytes == image.size && pendingBytes == 0
}
