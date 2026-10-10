package com.vythera.vyxelapps.delta

import com.vythera.vyxelapps.delta.DeltaPatchInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/**
 * Z-P13 (card "**S** apply"): the client applier is exercised against real patches Zealot's generator
 * produced, so "the client applies them and must match byte for byte" is a checked fact, not a claim. The
 * vectors are genuine output of `ArchivePatcher::FileByFile.generate` (captured from the generator, not
 * hand-written): a changed entry, an added entry, a removed entry, and a multi-region edit.
 *
 * The pure-Kotlin delta package has no Android dependency, so these run on the JVM on any toolchain.
 */
class FileByFileTest {

    private class V(val name: String, val old: String, val new: String, val patch: String)

    private fun bytes(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    private val vectors = listOf(
        V(
            name = "one-line",
            old = "UEsDBBQAAAAIAAAAAABQkWNJCgAAACADAAALAAAAY2xhc3Nlcy5kZXhLTBwFo2AU4AIAUEsDBBQAAAAIAAAAAACFEUoNDQAA"+"AAsAAAANAAAAcmVzL3Jhdy94LnR4dMtIzcnJVyjPL8pJAQBQSwECFAAUAAAACAAAAAAAUJFjSQoAAAAgAwAACwAAAAAAAAAA"+"AAAAAAAAAAAAY2xhc3Nlcy5kZXhQSwECFAAUAAAACAAAAAAAhRFKDQ0AAAALAAAADQAAAAAAAAAAAAAAAAAzAAAAcmVzL3Jh"+"dy94LnR4dFBLBQYAAAAAAgACAHQAAABrAAAAAAA=",
            new = "UEsDBBQAAAAIAAAAAACbL6zBDgAAACQDAAALAAAAY2xhc3Nlcy5kZXhLTBwFo2AU4AKuLp4hAFBLAwQUAAAACAAAAAAAhRFK"+"DQ0AAAALAAAADQAAAHJlcy9yYXcveC50eHTLSM3JyVcozy/KSQEAUEsBAhQAFAAAAAgAAAAAAJsvrMEOAAAAJAMAAAsAAAAA"+"AAAAAAAAAAAAAAAAAGNsYXNzZXMuZGV4UEsBAhQAFAAAAAgAAAAAAIURSg0NAAAACwAAAA0AAAAAAAAAAAAAAAAANwAAAHJl"+"cy9yYXcveC50eHRQSwUGAAAAAAIAAgB0AAAAbwAAAAAA",
            patch = "R0ZiRnYxXzAAAAAAAAAAAAAABAsAAAABAAAAAAAAACkAAAAAAAAACgAAAAEAAAAAAAAAKQAAAAAAAAMkAAQAAQAAAAEAAAAA"+"AAAAAAAAAAAAAAAECwAAAAAAAAAAAAAAAAAABA8AAAAAAAAA5EJTRElGRjQwAAAAAAAAAEUAAAAAAAAALQAAAAAAAAQPQlpo"+"OTFBWSZTWeoQzZkAAAJ8AH0oAIhAABAAAFAgACEiRNpNN6oQAAN1d2Yne6m/UkEGfMoGOUlCn+LuSKcKEh1CGbMgQlpoOTFB"+"WSZTWcdH2FAAAAHAAcAACAAACCAAIKptQZi6g8XckU4UJDHR9hQAQlpoOTFBWSZTWS7AgqwAABF/NGxBBAAEAICABihEAAAA"+"gAgABCAAIAAxTCaaA0xBKptTGgBkckgqiB9pU6VQcvuG20zP+508LuSKcKEgXYEFWA==",
        ),
        V(
            name = "added-entry",
            old = "UEsDBBQAAAAIAAAAAABvqnukCQAAAAcAAAALAAAAY2xhc3Nlcy5kZXhLSa3Qzc9LBQBQSwMEFAAAAAgAAAAAAAkZl4kHAAAA"+"LAEAAAUAAABhLnR4dEtMHAXEAgBQSwECFAAUAAAACAAAAAAAb6p7pAkAAAAHAAAACwAAAAAAAAAAAAAAAAAAAAAAY2xhc3Nl"+"cy5kZXhQSwECFAAUAAAACAAAAAAACRmXiQcAAAAsAQAABQAAAAAAAAAAAAAAAAAyAAAAYS50eHRQSwUGAAAAAAIAAgBsAAAA"+"XAAAAAAA",
            new = "UEsDBBQAAAAIAAAAAABvqnukCQAAAAcAAAALAAAAY2xhc3Nlcy5kZXhLSa3Qzc9LBQBQSwMEFAAAAAgAAAAAAAkZl4kHAAAA"+"LAEAAAUAAABhLnR4dEtMHAXEAgBQSwMEFAAAAAgAAAAAAMZq2usQAAAADgAAAAUAAABiLnR4dEsqSsxLUchLLVdIy8xJBQBQ"+"SwECFAAUAAAACAAAAAAAb6p7pAkAAAAHAAAACwAAAAAAAAAAAAAAAAAAAAAAY2xhc3Nlcy5kZXhQSwECFAAUAAAACAAAAAAA"+"CRmXiQcAAAAsAQAABQAAAAAAAAAAAAAAAAAyAAAAYS50eHRQSwECFAAUAAAACAAAAAAAxmra6xAAAAAOAAAABQAAAAAAAAAA"+"AAAAAABcAAAAYi50eHRQSwUGAAAAAAMAAwCfAAAAjwAAAAAA",
            patch = "R0ZiRnYxXzAAAAAAAAAAAAAAAN4AAAAAAAAAAQAAAAAAAAB/AAAAAAAAAA4AAQABAAAAAQAAAAAAAAAAAAAAAAAAAADeAAAA"+"AAAAAAAAAAAAAAABQgAAAAAAAAEBQlNESUZGNDAAAAAAAAAAPgAAAAAAAAAnAAAAAAAAAUJCWmg5MUFZJlNZBNWWbQAAAPdA"+"XAAECAAAgIAAAQABQAAgACGQA0IBppoUxu8sIIpiUOC8XckU4UJAE1ZZtEJaaDkxQVkmU1nkeOc2AAAAQARAAABAIAAhAIKD"+"F3JFOFCQ5HjnNkJaaDkxQVkmU1kaT3YSAAAdd+d/YUQAQAEACEAENzUUwAAAgACBAAAQAAggAFQ1TRoNGRoaHqA/VPUDIkaa"+"HqAAGgD+oB0EAoDBWF0vcgIDIS9CualkCOUXzKR8EjEog2OpcKCWcdqCWvUehLg8IFHw/F3JFOFCQGk92Eg=",
        ),
        V(
            name = "removed-entry",
            old = "UEsDBBQAAAAIAAAAAABvqnukCQAAAAcAAAALAAAAY2xhc3Nlcy5kZXhLSa3Qzc9LBQBQSwMEFAAAAAgAAAAAAM1787APAAAA"+"DQAAAAgAAABnb25lLnR4dCvJV0hKVShKzc0vS00BAFBLAwQUAAAACAAAAAAAC0jwywYAAAAEAAAACAAAAGtlZXAudHh0y05N"+"LQAAUEsBAhQAFAAAAAgAAAAAAG+qe6QJAAAABwAAAAsAAAAAAAAAAAAAAAAAAAAAAGNsYXNzZXMuZGV4UEsBAhQAFAAAAAgA"+"AAAAAM1787APAAAADQAAAAgAAAAAAAAAAAAAAAAAMgAAAGdvbmUudHh0UEsBAhQAFAAAAAgAAAAAAAtI8MsGAAAABAAAAAgA"+"AAAAAAAAAAAAAAAAZwAAAGtlZXAudHh0UEsFBgAAAAADAAMApQAAAJMAAAAAAA==",
            new = "UEsDBBQAAAAIAAAAAABvqnukCQAAAAcAAAALAAAAY2xhc3Nlcy5kZXhLSa3Qzc9LBQBQSwMEFAAAAAgAAAAAAAtI8MsGAAAA"+"BAAAAAgAAABrZWVwLnR4dMtOTS0AAFBLAQIUABQAAAAIAAAAAABvqnukCQAAAAcAAAALAAAAAAAAAAAAAAAAAAAAAABjbGFz"+"c2VzLmRleFBLAQIUABQAAAAIAAAAAAALSPDLBgAAAAQAAAAIAAAAAAAAAAAAAAAAADIAAABrZWVwLnR4dFBLBQYAAAAAAgAC"+"AG8AAABeAAAAAAA=",
            patch = "R0ZiRnYxXzAAAAAAAAAAAAAAAUwAAAABAAAAAAAAAFgAAAAAAAAADwAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAUwAAAAAAAAA"+"AAAAAAAAAADjAAAAAAAAAMtCU0RJRkY0MAAAAAAAAAA9AAAAAAAAACcAAAAAAAAA40JaaDkxQVkmU1kqndl0AAAA/QBMAAAY"+"GAAJAEAAAIAgACEoJjRCAaaaFKy97GBk+ucSfF3JFOFCQKp3ZdBCWmg5MUFZJlNZrcFcgQAAAEAIQAAAAiAAIQCCgxdyRThQ"+"kK3BXIFCWmg5MUFZJlNZ7+CD3QAAC9+AcwAAARAAAAhAAQIIxEAgACIaQ02oYhTAATTJZfQyK4QBTEntPVMgu9fF3JFOFCQ7"+"+CD3QA==",
        ),
        V(
            name = "multi-region",
            old = "UEsDBBQAAAAIAAAAAABRzrIKCAAAAPQBAAALAAAAY2xhc3Nlcy5kZXirqBgFIw0AAFBLAwQUAAAACAAAAAAAXFgWoggAAACQ"+"AQAABgAAAG0xLmJpbsvNHQWDCQAAUEsDBBQAAAAIAAAAAABQbnQ4CAAAAJABAAAGAAAAbTIuYmluy8sbBYMJAABQSwMEFAAA"+"AAgAAAAAALEz/AAFAAAAAwAAAAgAAAB0YWlsLnR4dEvNSwEAUEsBAhQAFAAAAAgAAAAAAFHOsgoIAAAA9AEAAAsAAAAAAAAA"+"AAAAAAAAAAAAAGNsYXNzZXMuZGV4UEsBAhQAFAAAAAgAAAAAAFxYFqIIAAAAkAEAAAYAAAAAAAAAAAAAAAAAMQAAAG0xLmJp"+"blBLAQIUABQAAAAIAAAAAABQbnQ4CAAAAJABAAAGAAAAAAAAAAAAAAAAAF0AAABtMi5iaW5QSwECFAAUAAAACAAAAAAAsTP8"+"AAUAAAADAAAACAAAAAAAAAAAAAAAAACJAAAAdGFpbC50eHRQSwUGAAAAAAQABADXAAAAtAAAAAAA",
            new = "UEsDBBQAAAAIAAAAAADblk9VCgAAAPYBAAALAAAAY2xhc3Nlcy5kZXirqBgFIw1ERQEAUEsDBBQAAAAIAAAAAABohRGsCQAA"+"AJEBAAAGAAAAbTEuYmluy80dBYMJBAIAUEsDBBQAAAAIAAAAAABQbnQ4CAAAAJABAAAGAAAAbTIuYmluy8sbBYMJAABQSwME"+"FAAAAAgAAAAAAPqADSIGAAAABAAAAAgAAAB0YWlsLnR4dEvNS1EEAFBLAQIUABQAAAAIAAAAAADblk9VCgAAAPYBAAALAAAA"+"AAAAAAAAAAAAAAAAAABjbGFzc2VzLmRleFBLAQIUABQAAAAIAAAAAABohRGsCQAAAJEBAAAGAAAAAAAAAAAAAAAAADMAAABt"+"MS5iaW5QSwECFAAUAAAACAAAAAAAUG50OAgAAACQAQAABgAAAAAAAAAAAAAAAABgAAAAbTIuYmluUEsBAhQAFAAAAAgAAAAA"+"APqADSIGAAAABAAAAAgAAAAAAAAAAAAAAAAAjAAAAHRhaWwudHh0UEsFBgAAAAAEAAQA1wAAALgAAAAAAA==",
            patch = "R0ZiRnYxXzAAAAAAAAAAAAAABRMAAAADAAAAAAAAACkAAAAAAAAACAAAAAAAAABVAAAAAAAAAAgAAAAAAAAArwAAAAAAAAAF"+"AAAAAwAAAAAAAAApAAAAAAAAAfYABAABAAAAAAAAAFcAAAAAAAABkQAEAAEAAAAAAAAAsgAAAAAAAAAEAAEAAQAAAAEAAAAA"+"AAAAAAAAAAAAAAAFEwAAAAAAAAAAAAAAAAAABRcAAAAAAAABL0JTRElGRjQwAAAAAAAAAE8AAAAAAAAALQAAAAAAAAUXQlpo"+"OTFBWSZTWV7OOScAAAPoIH0IAKhOEAACIABQpgAJoJTJTTIzU9TI/OLWui6xzWZJr6+EAGIpiUDbGWrQkTBDGTX4u5IpwoSC"+"9nHJOEJaaDkxQVkmU1mgYzTnAAACQIDAAAQAAAggACCqbUGYijni7kinChIUDGac4EJaaDkxQVkmU1ko3qfyAAA3f/r9ciQA"+"MAEIAAAI4hBmZQRAQgQhAAAEAEAACAEQIAB0EqKbSGmQ2p6JhNqDYR6oJREAamAEYmRgRuYUhVoMHJENHoyQaJEogkCob1MB"+"5gwcLQKomLMCBE7DGvcfm5qmYv8O0KN3ykujlrKLpw5obDuIhMTqMET+LuSKcKEgUb1P5A==",
        ),
    )

    private fun decoded(v: V) = Triple(bytes(v.old), bytes(v.new), bytes(v.patch))

    @Test
    fun `a real File-by-File patch reconstructs the new archive byte for byte`() {
        for (v in vectors) {
            val (old, expected, patch) = decoded(v)
            val got = FileByFile.apply(old, patch)
            assertArrayEquals("vector ${v.name}", expected, got)
        }
    }

    @Test
    fun `DeltaApplier applies when the recorded hashes match`() {
        for (v in vectors) {
            val (old, expected, patch) = decoded(v)
            val got = DeltaApplier.applyPatch(
                installedBase = old,
                patchBytes = patch,
                expectedPatchSha256 = DeltaApplier.sha256Hex(patch),
                expectedFromSha256 = DeltaApplier.sha256Hex(old),
                expectedToSha256 = DeltaApplier.sha256Hex(expected),
            )
            assertArrayEquals("vector ${v.name}", expected, got)
        }
    }

    @Test
    fun `a wrong to_sha256 refuses the result`() {
        val (old, _, patch) = decoded(vectors.first())
        assertThrows(DeltaApplier.ApplyError::class.java) {
            DeltaApplier.applyPatch(old, patch, expectedToSha256 = "00".repeat(32))
        }
    }

    @Test
    fun `a wrong from_sha256 refuses before decoding`() {
        val (old, _, patch) = decoded(vectors.first())
        val thrown = assertThrows(DeltaApplier.ApplyError::class.java) {
            DeltaApplier.applyPatch(old, patch, expectedFromSha256 = "11".repeat(32))
        }
        assertEquals(true, thrown.message!!.contains("from_sha256"))
    }

    @Test
    fun `a wrong patch sha256 refuses before decoding`() {
        val (old, _, patch) = decoded(vectors.first())
        assertThrows(DeltaApplier.ApplyError::class.java) {
            DeltaApplier.applyPatch(old, patch, expectedPatchSha256 = "22".repeat(32))
        }
    }

    @Test
    fun `garbage is not a patch`() {
        assertThrows(FileByFile.DeltaError::class.java) {
            FileByFile.apply("not an apk".toByteArray(), "not a patch at all".toByteArray())
        }
    }

    @Test
    fun `selectPatch takes only the exact installed version code`() {
        val patches = listOf(
            patch("100", "https://c.test/a"),
            patch("1.2.0", "https://c.test/b"),
        )
        assertSame(patches[1], DeltaApplier.selectPatch(patches, "1.2.0"))
        assertSame(patches[1], DeltaApplier.selectPatch(patches, " 1.2.0 "))
        assertNull("a normalised code must not match", DeltaApplier.selectPatch(patches, "1.2"))
        assertNull(DeltaApplier.selectPatch(patches, "999"))
    }

    @Test
    fun `selectPatch is null for no patches, an empty list, or a blank code`() {
        assertNull(DeltaApplier.selectPatch(null, "100"))
        assertNull(DeltaApplier.selectPatch(emptyList(), "100"))
        assertNull(DeltaApplier.selectPatch(listOf(patch("100", "https://c.test/a")), "  "))
    }

    private fun patch(code: String, url: String) = DeltaPatchInfo(fromVersionCode = code, downloadUrl = url)
}
