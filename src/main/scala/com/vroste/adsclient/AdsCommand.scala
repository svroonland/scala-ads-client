package com.vroste.adsclient

import java.nio.ByteBuffer

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
    case AdsReadCommand(_, _, _)                           => 2
    case AdsWriteCommand(_, _, _)                          => 3
    case AdsAddDeviceNotificationCommand(_, _, _, _, _, _) => 6
    case AdsDeleteDeviceNotificationCommand(_)             => 7
    case AdsWriteReadCommand(_, _, _, _)                   => 9
  }

  /***
    * Returns the 'data' bytes / payload of this command, excluding AMS headers
    * @return
    */
  def getBytes(c: AdsCommand): Array[Byte] = c match {
    case AdsReadCommand(indexGroup, indexOffset, readLength) =>
      ByteBuffer.allocate(12).putInt(indexGroup).putInt(indexOffset).putInt(readLength).array
    case AdsWriteCommand(indexGroup, indexOffset, values) =>
      ByteBuffer
        .allocate(12 + values.length)
        .putInt(indexGroup)
        .putInt(indexOffset)
        .putInt(values.length)
        .put(values)
        .array
    case AdsWriteReadCommand(indexGroup, indexOffset, values, readLength) =>
      ByteBuffer
        .allocate(16 + values.length)
        .putInt(indexGroup)
        .putInt(indexOffset)
        .putInt(readLength)
        .putInt(values.length)
        .put(values)
        .array
    case AdsAddDeviceNotificationCommand(indexGroup, indexOffset, readLength, transmissionMode, maxDelay, cycleTime) =>
      ByteBuffer
        .allocate(40)
        .putInt(indexGroup)
        .putInt(indexOffset)
        .putInt(readLength)
        .putInt(transmissionMode match { case Cyclic => 3; case OnChange => 4 })
        .putInt(maxDelay)
        .putInt(cycleTime)
        .put(ByteBuffer.allocateDirect(16)) // Reserved bytes
        .array
    case AdsDeleteDeviceNotificationCommand(notificationHandle) =>
      ByteBuffer.allocate(4).putInt(notificationHandle).array()
  }
}
