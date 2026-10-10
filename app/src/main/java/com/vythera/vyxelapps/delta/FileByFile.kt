package com.vythera.vyxelapps.delta

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The client half of Z-P13: apply the archive-patcher **File-by-File v1** patch Zealot builds at publish
 * time (see Zealot's `ArchivePatcher::FileByFile`), so an update downloads only the delta instead of the
 * whole APK. Owner **S** on the parity kanban; the generator half is **Z**.
 *
 * The container the generator writes (all integers big-endian, see the archive-patcher README "The
 * File-by-File v1 Patch Format"):
 *
 *   Versioned Identifier       8 bytes   "GFbFv1_0"
 *   Flags                      4 bytes   0
 *   Delta-friendly old size    8 bytes   uint64
 *   Num uncompression ops      4 bytes   uint32
 *   <uncompression op>         ...        {offset uint64, nbytes uint64}  x N
 *   Num recompression ops      4 bytes   uint32
 *   <recompression op>         ...        {offset uint64, size uint64, settings 4 bytes}  x N
 *   Num delta descriptors      4 bytes   1
 *   <delta descriptor>         ...        {format uint8, old start, old len, new start, new len, delta len}
 *   <delta>                    ...        the bsdiff stream
 *
 * Applying is three steps, exactly as the generator's own server-side twin (`FileByFile.apply`) does it:
 *  1. inflate each named region of the OLD archive into the "delta-friendly" space;
 *  2. run the bsdiff delta to reach the delta-friendly NEW blob;
 *  3. re-deflate each recorded region **in place** — deflate never changes a region's byte length, and the
 *     generator already shifted every later offset by that length change, so a single ascending pass is
 *     correct. This step exists to prove the patch reconstructs the new archive exactly; it is what makes
 *     the result byte-for-byte the new APK.
 *
 * Everything is in-memory `ByteArray` (APKs here run tens of MB, which the caller already holds as a file);
 * no Android APIs are used, so the class compiles and unit-tests off-device. The caller (see
 * [DeltaApplier]) owns the checksum/signer gate and the fallback to a full download.
 */
object FileByFile {

    private val MAGIC = "GFbFv1_0".toByteArray(Charsets.US_ASCII)
    private const val DELTA_FORMAT_ID = 0

    /** Thrown on anything that is not a well-formed v1 patch, or that does not reconstruct. */
    class DeltaError(message: String) : Exception(message)

    class UncompressionOp(val offset: Long, val size: Long)
    class RecompressionOp(val offset: Long, val size: Long, val level: Int, val strategy: Int)

    private class Parsed(
        val oldSpaceSize: Long,
        val newSpaceSize: Long,
        val uncompression: List<UncompressionOp>,
        val recompression: List<RecompressionOp>,
        val delta: ByteArray,
    )

    /**
     * Reconstruct the new archive from [oldBytes] (the installed base APK) and [patch] (the `GFbFv1_0`
     * bytes fetched from the index's `delta_patches[].download_url`).
     *
     * @throws DeltaError if the patch is malformed or does not reconstruct.
     */
    fun apply(oldBytes: ByteArray, patch: ByteArray): ByteArray {
        val parsed = parse(patch)
        val dfsOld = uncompressRanges(oldBytes, parsed.uncompression)
        if (dfsOld.size.toLong() != parsed.oldSpaceSize) {
            throw DeltaError("delta-friendly old space is ${dfsOld.size} bytes, expected ${parsed.oldSpaceSize}")
        }
        val dfsNew = BsDiff.patch(dfsOld, parsed.delta)
        if (dfsNew.size.toLong() != parsed.newSpaceSize) {
            throw DeltaError("delta-friendly new space is ${dfsNew.size} bytes, expected ${parsed.newSpaceSize}")
        }
        return recompress(dfsNew, parsed.recompression)
    }

    private fun parse(patch: ByteArray): Parsed {
        if (patch.size < 12 || !patch.copyOfRange(0, 8).contentEquals(MAGIC)) {
            throw DeltaError("not a File-by-File v1 patch")
        }
        val reader = Reader(patch, 12) // magic + flags
        val oldSpaceSize = reader.u64()
        val uncompression = ArrayList<UncompressionOp>()
        repeat(reader.u32()) { uncompression.add(UncompressionOp(reader.u64(), reader.u64())) }
        val recompression = ArrayList<RecompressionOp>()
        repeat(reader.u32()) {
            val offset = reader.u64()
            val size = reader.u64()
            reader.byte() // compression-window id; only 0 is defined
            val level = reader.byte()
            val strategy = reader.byte()
            reader.byte() // wrap mode; only "no wrap" (1) is defined
            recompression.add(RecompressionOp(offset, size, level, strategy))
        }
        val count = reader.u32()
        if (count != 1) throw DeltaError("expected exactly one delta descriptor, found $count")
        val format = reader.byte()
        if (format != DELTA_FORMAT_ID) throw DeltaError("unsupported delta format $format")
        reader.u64() // old space start
        reader.u64() // old space length
        reader.u64() // new space start
        val newSpaceSize = reader.u64()
        val deltaLen = reader.u64()
        val delta = reader.bytes(deltaLen)
        return Parsed(oldSpaceSize, newSpaceSize, uncompression, recompression, delta)
    }

    /** Splice the archive: copy untouched runs, inflate each named region in place. */
    private fun uncompressRanges(old: ByteArray, ops: List<UncompressionOp>): ByteArray {
        val out = ByteArrayOutputStream(old.size + 1024)
        var cursor = 0L
        for (op in ops) {
            if (op.offset > cursor) out.write(old, cursor.toInt(), (op.offset - cursor).toInt())
            out.write(inflate(old.copyOfRange(op.offset.toInt(), (op.offset + op.size).toInt())))
            cursor = op.offset + op.size
        }
        if (cursor < old.size) out.write(old, cursor.toInt(), (old.size - cursor).toInt())
        return out.toByteArray()
    }

    /**
     * Re-deflate each recorded region of the delta-friendly new blob in place. Ops are already ordered by
     * post-apply offset (the generator's `build_new_space` shifted each one), so a single ascending pass
     * over a buffer that grows to the archive size is correct.
     */
    private fun recompress(dfsNew: ByteArray, ops: List<RecompressionOp>): ByteArray {
        var out = dfsNew
        for (op in ops.sortedBy { it.offset }) {
            val start = op.offset.toInt()
            val end = (op.offset + op.size).toInt()
            val compressed = deflate(out.copyOfRange(start, end), op.level, op.strategy)
            val next = ByteArray(start + compressed.size + (out.size - end))
            System.arraycopy(out, 0, next, 0, start)
            System.arraycopy(compressed, 0, next, start, compressed.size)
            System.arraycopy(out, end, next, start + compressed.size, out.size - end)
            out = next
        }
        return out
    }

    /** Raw-deflate (no zlib header), matching Java's [Deflater] with `nowrap = true`. */
    private fun deflate(content: ByteArray, level: Int, strategy: Int): ByteArray {
        val deflater = Deflater(level.coerceIn(1, 9), true)
        deflater.setStrategy(strategy.coerceIn(0, 2))
        try {
            deflater.setInput(content)
            deflater.finish()
            val out = ByteArrayOutputStream(content.size + 64)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Raw-inflate (no zlib header), matching Zealot's `Zlib::Inflate.new(-Zlib::MAX_WBITS)`. */
    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 4 + 64)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && inflater.needsInput()) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw DeltaError("could not inflate a patch region: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    private class Reader(private val bytes: ByteArray, private var pos: Int) {
        fun byte(): Int {
            if (pos + 1 > bytes.size) throw DeltaError("truncated patch")
            return bytes[pos++].toInt() and 0xff
        }

        fun u32(): Int {
            var v = 0
            repeat(4) { v = (v shl 8) or byte() }
            return v
        }

        fun u64(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or byte().toLong() }
            return v
        }

        fun bytes(n: Long): ByteArray {
            if (n < 0 || pos + n > bytes.size) throw DeltaError("truncated patch")
            val out = bytes.copyOfRange(pos, (pos + n).toInt())
            pos += n.toInt()
            return out
        }
    }
}
