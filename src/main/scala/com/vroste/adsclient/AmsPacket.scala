package com.vroste.adsclient

import java.nio.{ByteBuffer, ByteOrder}

case class AmsPacket(amsHeader: AmsHeader, data: Array[Byte]) {
  def toBytes: Array[Byte] = {
    val headerBytes         = amsHeader.getBytes(data.length)
    val headerAndDataLength = headerBytes.length + data.length

    ByteBuffer
      .allocate(6 + headerAndDataLength)
      .order(ByteOrder.LITTLE_ENDIAN)
      .put(0.toByte)
      .put(0.toByte)
      .putInt(headerAndDataLength)
      .put(headerBytes)
      .put(data)
      .array()
  }

  def debugString: String = toBytes.map("%02X" format _).mkString(" ")
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
