package com.vythera.vyxelapps.api

/**
 * Track h, leaf `h.ii.zi`: the pure rules of the self-update download. Nothing here touches the network, the
 * disk, the clock or Android, so every rule runs as a plain unit test; `SelfUpdateDownloader.kt` (the worker)
 * does the I/O and asks these functions what to do.
 *
 * The one rule that matters most: **a file is accepted only when its byte count equals the index's
 * `size_bytes`.** A short file (the connection dropped, or the server stopped early) and a long file (the
 * server sent more than the index lists) are both refused and deleted, never handed to the installer. D-Store's
 * own download door has a truncation risk; this path must not inherit it.
 */

/** What the banner and the worker's notification show. `versionCode` ties a state to the offer it belongs to. */
sealed class SelfUpdateDownloadState {
    object Idle : SelfUpdateDownloadState()
    data class Downloading(val versionCode: Long, val bytesDone: Long, val totalBytes: Long) : SelfUpdateDownloadState() {
        /** 0 to 100, rounded down; 0 when the total is not above zero. */
        val percent: Int get() = if (totalBytes <= 0L) 0 else ((bytesDone.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
    }
    /** The file is complete and has the listed size. Its checksum and signer are NOT checked yet (h.iii.zi). */
    data class Downloaded(val versionCode: Long, val filePath: String, val sizeBytes: Long) : SelfUpdateDownloadState()
    data class Failed(val versionCode: Long, val reason: String) : SelfUpdateDownloadState()
}

/** What to do with a part-file already on disk before the request is made. */
sealed class ResumePlan {
    /** Nothing on disk: request the whole file. */
    object StartFresh : ResumePlan()
    /** A shorter part-file: request `Range: bytes=offset-`. */
    data class ResumeAt(val offset: Long) : ResumePlan()
    /** The part-file already has exactly the listed size: no request is needed, go straight to acceptance. */
    object AlreadyComplete : ResumePlan()
    /** The part-file is longer than the listed size, so it cannot be a prefix of this file: delete it and start over. */
    object DiscardAndRestart : ResumePlan()
}

/** What to do with the server's answer. */
sealed class ResponsePlan {
    /** Write the body into the part-file starting at [startOffset]; 0 means a new file, above 0 means append. */
    data class Write(val startOffset: Long) : ResponsePlan()
    /** The part-file cannot be continued (the server refused the range): delete it and ask again from zero. */
    object RestartFresh : ResponsePlan()
    /** Give up on this answer; [reason] is a plain sentence the banner can show. */
    data class Fail(val reason: String) : ResponsePlan()
}

sealed class FinalVerdict {
    object Accept : FinalVerdict()
    data class TooShort(val missingBytes: Long) : FinalVerdict()
    data class TooLong(val extraBytes: Long) : FinalVerdict()
}

/** A parsed `Content-Range: bytes start-end/total` header; [total] is null when the server wrote `*`. */
data class ContentRange(val start: Long, val end: Long, val total: Long?)

object SelfUpdateDownloadRules {

    /** After this many attempts at one offer, an interrupted download is given up and its part-file deleted. */
    const val MAX_ATTEMPTS = 3

    fun planResume(partialLength: Long, expectedSize: Long): ResumePlan = when {
        partialLength <= 0L -> ResumePlan.StartFresh
        partialLength == expectedSize -> ResumePlan.AlreadyComplete
        partialLength < expectedSize -> ResumePlan.ResumeAt(partialLength)
        else -> ResumePlan.DiscardAndRestart
    }

    /**
     * @param code the HTTP status
     * @param requestedOffset the offset the request asked for (0 when no Range header was sent)
     * @param contentRangeHeader the response's `Content-Range` header, or null
     * @param contentLength the response body's length, or -1 when the server did not say
     * @param expectedSize the index's `size_bytes`
     */
    fun planResponse(
        code: Int,
        requestedOffset: Long,
        contentRangeHeader: String?,
        contentLength: Long,
        expectedSize: Long
    ): ResponsePlan {
        when (code) {
            200 -> {
                // The whole file, from byte zero. If a Range was asked for, the server ignored it, so the part-file
                // is discarded and the body is written from the start.
                if (contentLength >= 0L && contentLength != expectedSize) {
                    return ResponsePlan.Fail(
                        "The server says the file is $contentLength bytes but the update lists $expectedSize."
                    )
                }
                return ResponsePlan.Write(0L)
            }
            206 -> {
                val range = parseContentRange(contentRangeHeader)
                    ?: return ResponsePlan.Fail("The server sent part of the file without saying which part.")
                if (range.start != requestedOffset) {
                    return ResponsePlan.Fail(
                        "The server sent bytes from ${range.start} but ${requestedOffset} were asked for."
                    )
                }
                if (range.total != null && range.total != expectedSize) {
                    return ResponsePlan.Fail(
                        "The server says the file is ${range.total} bytes but the update lists $expectedSize."
                    )
                }
                if (contentLength >= 0L && requestedOffset + contentLength != expectedSize) {
                    return ResponsePlan.Fail(
                        "The server's answer would end at ${requestedOffset + contentLength} bytes, not $expectedSize."
                    )
                }
                return ResponsePlan.Write(requestedOffset)
            }
            416 -> return if (requestedOffset > 0L) {
                ResponsePlan.RestartFresh
            } else {
                ResponsePlan.Fail("The server refused the download (416).")
            }
            else -> return ResponsePlan.Fail("The server refused the download ($code).")
        }
    }

    /** Parses `bytes 1000-31238329/31238330` (or a star for the total); null for anything else, including a reversed range. */
    fun parseContentRange(header: String?): ContentRange? {
        val text = header?.trim() ?: return null
        val match = CONTENT_RANGE.matchEntire(text) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val totalText = match.groupValues[3]
        val total = if (totalText == "*") null else (totalText.toLongOrNull() ?: return null)
        return ContentRange(start, end, total)
    }

    /** The only place that decides a download is complete: the byte count must equal the listed size. */
    fun judgeFinalSize(actualBytes: Long, expectedSize: Long): FinalVerdict = when {
        actualBytes == expectedSize -> FinalVerdict.Accept
        actualBytes < expectedSize -> FinalVerdict.TooShort(expectedSize - actualBytes)
        else -> FinalVerdict.TooLong(actualBytes - expectedSize)
    }

    /** True while another attempt is allowed. [runAttemptCount] is WorkManager's count, 0 on the first run. */
    fun mayRetry(runAttemptCount: Int): Boolean = runAttemptCount + 1 < MAX_ATTEMPTS

    /** Room needed before a download starts: what is still to fetch plus a margin for the installer's own copy. */
    fun enoughSpace(usableBytes: Long, expectedSize: Long, alreadyHaveBytes: Long, marginBytes: Long = 8L * 1024 * 1024): Boolean =
        usableBytes >= (expectedSize - alreadyHaveBytes).coerceAtLeast(0L) + marginBytes

    private val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)
}
