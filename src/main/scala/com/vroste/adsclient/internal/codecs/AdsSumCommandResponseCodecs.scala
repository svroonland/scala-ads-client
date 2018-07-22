package com.vroste.adsclient.internal.codecs

import com.vroste.adsclient.internal.AdsResponse.AdsWriteReadCommandResponse
import com.vroste.adsclient.internal.util.CodecUtil.SequenceDecoders
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Codec, Decoder}

/**
  * Codecs for the payload of the response to a writeread command for sum commands
  */
trait AdsSumCommandResponseCodecs extends AdsResponseCodecs {

  import com.vroste.adsclient.internal.AdsSumCommandResponses._

  /**
    * The payload of the response of an ADS Sum WriteRead command is encoded as a list of (errorCode, readLength)
    * followed by the data of the individual write read commands
    *
    * We map it to a list of regular write read command responses, but that takes some trickery to get right
    */
  def adsSumWriteReadCommandResponseDecoder(nrValues: Int): Decoder[AdsSumWriteReadCommandResponse] = {
    val errorsAndValuesCodec: Decoder[Seq[(Long, BitVector)]] =
      listOfN(provide(nrValues), ("errorCode" | errorCodeCodec) ~ ("length" | uint32L))
        .withContext("WriteRead response error codes and lengths")
        .flatMap[Seq[(Long, BitVector)]] { errorCodesAndLengths =>
        val (errorCodes, lengths) = errorCodesAndLengths.unzip

        val valueCodecs = lengths.zipWithIndex.map { case (length, index) =>
          bits(length.toInt * 8).withContext(s"WriteRead sub response value ${index + 1}")
        }

        valueCodecs.sequence.map(errorCodes zip _)
      }

    errorsAndValuesCodec
      .map(_.map(AdsWriteReadCommandResponse.tupled).toList)
      .map(AdsSumWriteReadCommandResponse)
  }

  /**
    * The payload of the response of an ADS Sum Write command is a list of error codes
    *
    * We can easily compose it from the single ADS write command response codec
    */
  implicit val adsSumWriteCommandResponseCodec: Codec[AdsSumWriteCommandResponse] =
    list(adsWriteCommandResponseCodec).xmap(AdsSumWriteCommandResponse, _.responses)
}
