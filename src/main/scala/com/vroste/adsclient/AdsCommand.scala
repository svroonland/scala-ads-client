package com.vroste.adsclient

import java.nio.{ByteBuffer, ByteOrder}

import com.vroste.adsclient.AdsResponse.{AdsAddDeviceNotificationCommandResponse, AdsDeleteDeviceNotificationCommandResponse, AdsReadCommandResponse, AdsWriteReadCommandResponse}
import com.vroste.adsclient.AdsTransmissionMode.{Cyclic, OnChange}

/**
  * Commands to the ADS server
  */
sealed trait AdsCommand { self =>
  type ResponseType
}

object AdsCommand {

  case class AdsReadCommand(indexGroup: Int, indexOffset: Int, readLength: Int) extends AdsCommand {
    override type ResponseType = AdsReadCommandResponse
  }

  case class AdsWriteCommand(indexGroup: Int, indexOffset: Int, values: Array[Byte]) extends AdsCommand {
    override type ResponseType = AdsWriteReadCommandResponse.type
  }

  case class AdsWriteReadCommand(indexGroup: Int, indexOffset: Int, values: Array[Byte], readLength: Int)
      extends AdsCommand {
    override type ResponseType = AdsWriteReadCommandResponse
  }

  case class AdsAddDeviceNotificationCommand(indexGroup: Int,
                                             indexOffset: Int,
                                             readLength: Int,
                                             transmissionMode: AdsTransmissionMode,
                                             maxDelay: Int,
                                             cycleTime: Int)
      extends AdsCommand {
    override type ResponseType = AdsAddDeviceNotificationCommandResponse
  }

  case class AdsDeleteDeviceNotificationCommand(notificationHandle: Int) extends AdsCommand {
    override type ResponseType = AdsDeleteDeviceNotificationCommandResponse.type
  }

  def commandId(c: AdsCommand): Short = c match {
    case AdsReadCommand(_, _, _)                           => 0x0002
    case AdsWriteCommand(_, _, _)                          => 0x0003
    case AdsAddDeviceNotificationCommand(_, _, _, _, _, _) => 0x0006
    case AdsDeleteDeviceNotificationCommand(_)             => 0x0007
    case AdsWriteReadCommand(_, _, _, _)                   => 0x0009
  }

  /***
    * Returns the 'data' bytes / payload of this command, excluding AMS headers
    * @return
    */
  def getBytes(c: AdsCommand): Array[Byte] = c match {
    case AdsReadCommand(indexGroup, indexOffset, readLength) =>
      ByteBuffer
        .allocate(12)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(indexGroup)
        .putInt(indexOffset)
        .putInt(readLength)
        .array
    case AdsWriteCommand(indexGroup, indexOffset, values) =>
      ByteBuffer
        .allocate(12 + values.length)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(indexGroup)
        .putInt(indexOffset)
        .putInt(values.length)
        .put(values)
        .array
    case AdsWriteReadCommand(indexGroup, indexOffset, values, readLength) =>
      ByteBuffer
        .allocate(16 + values.length)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(indexGroup)
        .putInt(indexOffset)
        .putInt(readLength)
        .putInt(values.length)
        .put(values)
        .array
    case AdsAddDeviceNotificationCommand(indexGroup, indexOffset, readLength, transmissionMode, maxDelay, cycleTime) =>
      ByteBuffer
        .allocate(40)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(indexGroup)
        .putInt(indexOffset)
        .putInt(readLength)
        .putInt(transmissionMode match { case Cyclic => 3; case OnChange => 4 })
        .putInt(maxDelay)
        .putInt(cycleTime)
        .put(ByteBuffer.allocateDirect(16)) // Reserved bytes
        .array
    case AdsDeleteDeviceNotificationCommand(notificationHandle) =>
      ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(notificationHandle).array()
  }
}
