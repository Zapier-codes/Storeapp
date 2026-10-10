package com.vythera.vyxelapps.delta

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * The bsdiff stream reader that [FileByFile] runs inside the delta-friendly space. It is the client twin
 * of Zealot's `ArchivePatcher::BsDiff` (`app/services/archive_patcher/bsdiff.rb`): the same container
 * Google's archive-patcher emits, produced and consumed by that program's own pure-Ruby implementation.
 *
 * Format (as the reference implementation writes it):
 *   "BSDIFF40"                        (8 bytes, magic)
 *   length of the control block       (8 bytes, sign-magnitude, big-endian)
 *   length of the diff block          (8 bytes, sign-magnitude, big-endian)
 *   length of the new file            (8 bytes, sign-magnitude, big-endian)
 *   <control block>  bzip2 of N triples of three sign-magnitude 8-byte ints: (x, y, z)
 *   <diff block>     bzip2 of bytes added to `old` to form new regions
 *   <extra block>    bzip2 of literal bytes
 *
 * A triple means: copy `x` bytes from the diff block, each added (mod 256) to `old` at the current old
 * offset, into the output; then copy `y` bytes from the extra block; then seek the old offset by `z`.
 *
 * Only [patch] (apply) is implemented here — the client never makes a delta, Zealot does. The three blocks
 * are bzip2 streams; the JDK has no bzip2, so [bunzip] uses Apache Commons Compress (see the app's
 * `build.gradle.kts`).
 */
object BsDiff {

    private val MAGIC = "BSDIFF40".toByteArray(Charsets.US_ASCII)
    private const val BLOCK = 24

    class DeltaError(message: String) : Exception(message)

    /** @return the reconstructed target bytes. */
    fun patch(old: ByteArray, patch: ByteArray): ByteArray {
        if (patch.size < 32 || !patch.copyOfRange(0, 8).contentEquals(MAGIC)) {
            throw DeltaError("not a bsdiff stream")
        }
        val ctrlLen = readI64(patch, 8)
        val diffLen = readI64(patch, 16)
        val newSize = readI64(patch, 24)
        if (ctrlLen < 0 || diffLen < 0 || newSize < 0) throw DeltaError("negative block length")

        val ctrl = bunzip(patch, 32, ctrlLen.toInt())
        val diff = bunzip(patch, 32 + ctrlLen.toInt(), diffLen.toInt())
        val extraLen = patch.size.toLong() - 32 - ctrlLen - diffLen
        if (extraLen < 0) throw DeltaError("truncated bsdiff stream")
        val extra = bunzip(patch, 32 + ctrlLen.toInt() + diffLen.toInt(), extraLen.toInt())

        val out = ByteArrayOutputStream(if (newSize > Int.MAX_VALUE) 0 else newSize.toInt())
        var oldpos = 0L
        var cpos = 0
        var dpos = 0
        var epos = 0

        while (out.size().toLong() < newSize) {
            if (cpos + BLOCK > ctrl.size) break
            val x = readI64(ctrl, cpos)
            val y = readI64(ctrl, cpos + 8)
            val z = readI64(ctrl, cpos + 16)
            cpos += BLOCK

            if (x > 0) {
                if (oldpos < 0 || oldpos + x > old.size) throw DeltaError("old offset out of range")
                for (i in 0 until x.toInt()) {
                    val b = (old[(oldpos + i).toInt()].toInt() + diff[dpos + i].toInt()) and 0xff
                    out.write(b)
                }
            }
            oldpos += x
            dpos += x.toInt()
            if (y > 0) {
                out.write(extra, epos, y.toInt())
                epos += y.toInt()
            }
            oldpos += z
        }

        val result = out.toByteArray()
        return if (result.size > newSize) result.copyOf(newSize.toInt()) else result
    }

    /**
     * Sign-magnitude 64-bit, big-endian (the reference format): the top bit of the first byte is the sign,
     * the remaining 63 bits are the magnitude.
     */
    private fun readI64(bytes: ByteArray, offset: Int): Long {
        if (bytes.size < offset + 8) throw DeltaError("truncated integer")
        val negative = (bytes[offset].toInt() and 0x80) != 0
        var v = (bytes[offset].toInt() and 0x7f).toLong()
        for (k in 1..7) v = (v shl 8) or (bytes[offset + k].toLong() and 0xff)
        return if (negative) -v else v
    }

    /** Decompress one bzip2 stream (the JDK has no bzip2; the caller supplies Commons Compress). */
    private fun bunzip(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        if (length < 0 || offset + length > bytes.size) throw DeltaError("truncated bzip2 block")
        val slice = bytes.copyOfRange(offset, offset + length)
        return org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
            slice.inputStream()
        ).use { it.readBytes() }
    }
}
