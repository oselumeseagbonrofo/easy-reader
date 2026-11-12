package com.ebookreader.app.data.parser.epub

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

/**
 * Zero-copy O(1) random-access EPUB (ZIP) reader backed by a [SeekableByteChannel]
 * (such as a [java.nio.channels.FileChannel] from Android's SAF ParcelFileDescriptor).
 *
 * Parses the ZIP Central Directory once on open so any chapter or embedded image can be
 * fetched directly by byte offset without scanning earlier entries via ZipInputStream.
 */
class EpubRandomAccessArchive private constructor(
    private val channel: SeekableByteChannel,
    private val closeChannelOnClose: Boolean,
    private val entries: LinkedHashMap<String, EntryRecord>
) : Closeable {

    data class EntryRecord(
        val path: String,
        val compressionMethod: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Long
    )

    private val channelLock = Any()

    @Volatile
    private var isClosed = false

    val entryPaths: Set<String>
        get() = entries.keys

    fun hasEntry(path: String): Boolean {
        val norm = EpubParser.normalizeZipPath(path)
        return entries.containsKey(norm)
    }

    /**
     * O(1) direct byte-offset lookup and decompression of a single ZIP entry.
     */
    fun readEntry(path: String, maxBytes: Int = 16 * 1024 * 1024): ByteArray? {
        val normalized = EpubParser.normalizeZipPath(path)
        val record = entries[normalized]
            ?: entries.entries.firstOrNull { it.key.equals(normalized, ignoreCase = true) }?.value
            ?: return null

        if (record.compressedSize < 0 || record.uncompressedSize > maxBytes) return null
        if (record.uncompressedSize == 0 && record.compressedSize == 0) return ByteArray(0)

        val rawCompressed = synchronized(channelLock) {
            if (isClosed || !channel.isOpen) return null
            try {
                val localHeader = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                if (!readFullyAt(record.localHeaderOffset, localHeader, 30)) {
                    null
                } else {
                    localHeader.flip()
                    if (localHeader.int != LOCAL_FILE_HEADER_SIG) {
                        null
                    } else {
                        val fileNameLen = localHeader.getShort(26).toInt() and 0xFFFF
                        val extraLen = localHeader.getShort(28).toInt() and 0xFFFF
                        val dataOffset = record.localHeaderOffset + 30L + fileNameLen + extraLen

                        // Allocate +1 dummy trailing byte as required by java.util.zip.Inflater(nowrap = true)
                        val allocSize = if (record.compressionMethod == METHOD_DEFLATED) {
                            record.compressedSize + 1
                        } else {
                            record.compressedSize
                        }
                        val buf = ByteArray(allocSize)
                        val dataBuf = ByteBuffer.wrap(buf, 0, record.compressedSize)
                        if (readFullyAt(dataOffset, dataBuf, record.compressedSize)) buf else null
                    }
                }
            } catch (_: java.io.IOException) {
                null
            }
        } ?: return null

        return when (record.compressionMethod) {
            METHOD_STORED -> rawCompressed
            METHOD_DEFLATED -> inflateRaw(rawCompressed, record.uncompressedSize, maxBytes)
            else -> null
        }
    }

    private fun readFullyAt(offset: Long, target: ByteBuffer, byteCount: Int): Boolean {
        if (offset < 0) return false
        channel.position(offset)
        var total = 0
        while (total < byteCount) {
            val n = channel.read(target)
            if (n <= 0) return false
            total += n
        }
        return true
    }

    private fun inflateRaw(compressedWithDummy: ByteArray, expectedSize: Int, maxBytes: Int): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(compressedWithDummy)
            val initialCap = expectedSize.coerceIn(256, 256 * 1024)
            val out = ByteArrayOutputStream(initialCap)
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count > 0) {
                    total += count
                    if (total > maxBytes) return null
                    out.write(buf, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    override fun close() {
        isClosed = true
        synchronized(channelLock) {
            if (closeChannelOnClose) {
                runCatching { channel.close() }
            }
        }
    }

    companion object {
        private const val EOCD_SIG = 0x06054b50
        private const val CENTRAL_DIR_SIG = 0x02014b50
        private const val LOCAL_FILE_HEADER_SIG = 0x04034b50
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val MAX_EOCD_SEARCH = 65_557

        fun openOrNull(
            channel: SeekableByteChannel,
            closeChannelOnClose: Boolean = true
        ): EpubRandomAccessArchive? {
            return runCatching {
                val size = channel.size()
                if (size < 22L) return null

                val tailSize = minOf(size, MAX_EOCD_SEARCH.toLong()).toInt()
                val tailOffset = size - tailSize
                val tailBuf = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN)
                channel.position(tailOffset)
                var readTotal = 0
                while (readTotal < tailSize) {
                    val r = channel.read(tailBuf)
                    if (r <= 0) break
                    readTotal += r
                }
                if (readTotal < 22) return null

                var eocdPos = -1
                for (i in (readTotal - 22) downTo 0) {
                    if (tailBuf.getInt(i) == EOCD_SIG) {
                        eocdPos = i
                        break
                    }
                }
                if (eocdPos < 0) return null

                val cdSizeLong = tailBuf.getInt(eocdPos + 12).toLong() and 0xFFFFFFFFL
                val cdOffset = tailBuf.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL
                if (cdOffset == 0xFFFFFFFFL || cdSizeLong <= 0L || cdSizeLong > 32L * 1024L * 1024L) {
                    return null
                }

                val cdSize = cdSizeLong.toInt()
                val cdBuf = ByteBuffer.allocate(cdSize).order(ByteOrder.LITTLE_ENDIAN)
                channel.position(cdOffset)
                var cdRead = 0
                while (cdRead < cdSize) {
                    val r = channel.read(cdBuf)
                    if (r <= 0) break
                    cdRead += r
                }
                if (cdRead < cdSize) return null

                val entries = LinkedHashMap<String, EntryRecord>()
                var cursor = 0
                while (cursor + 46 <= cdSize) {
                    if (cdBuf.getInt(cursor) != CENTRAL_DIR_SIG) break
                    val method = cdBuf.getShort(cursor + 10).toInt() and 0xFFFF
                    val compSize = cdBuf.getInt(cursor + 20)
                    val uncompSize = cdBuf.getInt(cursor + 24)
                    val nameLen = cdBuf.getShort(cursor + 28).toInt() and 0xFFFF
                    val extraLen = cdBuf.getShort(cursor + 30).toInt() and 0xFFFF
                    val commentLen = cdBuf.getShort(cursor + 32).toInt() and 0xFFFF
                    val localOffset = cdBuf.getInt(cursor + 42).toLong() and 0xFFFFFFFFL

                    if (cursor + 46 + nameLen > cdSize) break
                    val nameBytes = ByteArray(nameLen)
                    cdBuf.position(cursor + 46)
                    cdBuf.get(nameBytes)
                    val rawName = String(nameBytes, StandardCharsets.UTF_8)

                    if (!rawName.endsWith("/") && compSize >= 0 && uncompSize >= 0) {
                        val normalized = EpubParser.normalizeZipPath(rawName)
                        if (normalized.isNotEmpty()) {
                            entries[normalized] = EntryRecord(
                                path = normalized,
                                compressionMethod = method,
                                compressedSize = compSize,
                                uncompressedSize = uncompSize,
                                localHeaderOffset = localOffset
                            )
                        }
                    }

                    cursor += 46 + nameLen + extraLen + commentLen
                }

                if (entries.isEmpty()) null else EpubRandomAccessArchive(channel, closeChannelOnClose, entries)
            }.getOrNull()
        }
    }
}
