package com.vroste.adsclient

import java.nio.ByteBuffer

case class AmsPacket(amsHeader: AmsHeader, data: Array[Byte]) {
  def toBytes: Array[Byte] = {
    val headerBytes         = amsHeader.getBytes(data.length)
    val headerAndDataLength = headerBytes.size + data.length

    ByteBuffer
      .allocate(6 + headerAndDataLength)
      .put(0.toByte)
      .put(0.toByte)
      .putInt(headerAndDataLength)
      .put(headerBytes)
      .put(data)
      .array()
  }
}

object AmsPacket {
  def fromBytes(bytes: Array[Byte]): AmsPacket = {
    val bb = ByteBuffer.wrap(bytes)

    bb.get() // Reserved
    bb.get() // Reserved
    bb.getInt // Length

    val header = AmsHeader.fromByteBuffer(bb)
    val data   = bb.slice().array()

    AmsPacket(header, data)
  }
}
