package com.vroste.adsclient.internal

import com.vroste.adsclient.internal.codecs.AdsSumCommandCodecs
import scodec.bits.ByteVector
import scodec.{Attempt, Codec}
import scodec.codecs._

import AdsCommand._

// SUM COMMANDS
// Sum commands are basically a list of commands of a type (read, write or write+read).
// They are encoded as write-read commands with a special encoding of the list of sub commands
// We encode them as a list of regular ADS commands with a method to convert them to the WriteReadCommand
// for better composability
object AdsSumCommand extends AdsSumCommandCodecs {

  case class AdsSumReadCommand(commands: Seq[AdsReadCommand]) {
    def toAdsCommand: Attempt[AdsWriteReadCommand] = {
      val requestParts = commands.map(c => SumReadRequestPart(c.indexGroup, c.indexOffset, c.readLength))

      for {
        requestPartsBits <- list(Codec[SumReadRequestPart]).encode(requestParts.toList)
      } yield AdsWriteReadCommand(
        indexGroup = 0xf080,
        indexOffset = commands.length,
        values = requestPartsBits.toByteVector,
        readLength = commands.map(_.readLength).sum + errorCodeSize * commands.length
      )
    }
  }

  case class AdsSumWriteCommand(commands: Seq[AdsWriteCommand], values: ByteVector) {
    def toAdsCommand: Attempt[AdsWriteReadCommand] = {
      val requestParts = commands.map(c => SumWriteRequestPart(c.indexGroup, c.indexOffset, c.values.length))
      val valueBytes = ByteVector.concat(commands.map(_.values))

      for {
        requestPartsBits <- list(Codec[SumWriteRequestPart]).encode(requestParts.toList)
      } yield AdsWriteReadCommand(
        indexGroup = 0xf081,
        indexOffset = commands.length,
        values = requestPartsBits.toByteVector ++ valueBytes,
        readLength = errorCodeSize * commands.length
      )
    }
  }

  // A sum write read command is an ADS write/read command to a specific indexGroup with the sub write/read commands
  // encoded as the payload. The values to be written are encoded at the end
  case class AdsSumWriteReadCommand(commands: Seq[AdsWriteReadCommand]) {
    def toAdsCommand: Attempt[AdsWriteReadCommand] = {
      val requestParts = commands.map(c => SumReadWriteRequestPart(c.indexGroup, c.indexOffset, c.readLength, c.values.length))
      val valueBytes = ByteVector.concat(commands.map(_.values))

      for {
        requestPartsBits <- list(Codec[SumReadWriteRequestPart]).encode(requestParts.toList)
      } yield AdsWriteReadCommand(
        indexGroup = 0xf082,
        indexOffset = commands.length,
        readLength = commands.map(_.readLength).sum + errorCodeSize * commands.length,
        values = requestPartsBits.toByteVector ++ valueBytes
      )
    }
  }

  case class SumReadWriteRequestPart(indexGroup: Long, indexOffset: Long, readLength: Long, writeLength: Long)

  case class SumWriteRequestPart(indexGroup: Long, indexOffset: Long, length: Long)

  case class SumReadRequestPart(indexGroup: Long, indexOffset: Long, length: Long)

  val errorCodeSize = 4
}
