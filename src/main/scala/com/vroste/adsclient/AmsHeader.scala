package com.vroste.adsclient

import java.nio.{ByteBuffer, ByteOrder}

// See https://infosys.beckhoff.de/index.php?content=../content/1031/tc3_adscommon/html/tcadsamsspec_amstcppackage.htm&id=
case class AmsHeader(amsNetIdTarget: AmsNetId,
                     amsPortTarget: Int,
                     amsNetIdSource: AmsNetId,
                     amsPortSource: Int,
                     commandId: Short,
                     stateFlags: Short,
                     errorCode: Int,
                     invokeId: Int) {

  def getBytes(dataLength: Int): Array[Byte] = {
    val buffer = ByteBuffer.allocate(32)
        .order(ByteOrder.LITTLE_ENDIAN)

    buffer
      .put(amsNetIdTarget.bytes) // 6 bytes
      .putShort(amsPortTarget.toShort)
      .put(amsNetIdSource.bytes) // 6 bytes
      .putShort(amsPortSource.toShort)
      .putShort(commandId)
      .putShort(stateFlags)
      .putInt(dataLength)
      .putInt(errorCode)
      .putInt(invokeId)
      .array()
  }
}

object AmsHeader {
  // Note: the byte buffer is mutated
  def fromByteBuffer(bb: ByteBuffer): AmsHeader = {
    val amsNetIdTargetBytes = new Array[Byte](6)
    bb.get(amsNetIdTargetBytes)
    val amsNetIdTarget = AmsNetId.fromBytes(amsNetIdTargetBytes)

    val amsPortTarget = bb.getShort()

    val amsNetIdSourceBytes = new Array[Byte](6)
    bb.get(amsNetIdSourceBytes)
    val amsNetIdSource = AmsNetId.fromBytes(amsNetIdSourceBytes)

    val amsPortSource = bb.getShort()
    val commandId     = bb.getShort()
    val stateFlags    = bb.getShort()
    val errorCode     = bb.getInt()
    val invokeId      = bb.getInt()

    AmsHeader(amsNetIdTarget, amsPortTarget, amsNetIdSource, amsPortSource, commandId, stateFlags, errorCode, invokeId)
  }
}
