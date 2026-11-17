package eu.bmesoft.insvmagik.insv

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Random read access to a file, so the trailer can be read without loading a multi-GB video. */
interface ByteSource {
    val size: Long

    /** Exactly [length] bytes from [position]; throws if the file is shorter. */
    fun read(position: Long, length: Int): ByteArray
}

/** A file opened through the file picker (`ParcelFileDescriptor` → `FileInputStream.channel`). */
class ChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long get() = channel.size()

    override fun read(position: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw EOFException("End of file at $pos")
            pos += n
        }
        return buf.array()
    }
}

/** Bytes in memory: unit tests and small records. */
class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || length < 0 || position + length > bytes.size) throw EOFException("Out of range")
        return bytes.copyOfRange(position.toInt(), position.toInt() + length)
    }
}

internal fun ByteArray.littleEndian(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
