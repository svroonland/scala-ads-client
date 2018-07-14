package com.vroste.adsclient.internal.codecs

import com.vroste.adsclient.internal.AdsResponse.AdsWriteReadCommandResponse
import com.vroste.adsclient.internal.util.CodecUtil.SequenceDecoders
import scodec.bits.ByteVector
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
    val errorsAndValuesCodec: Decoder[Seq[(Long, ByteVector)]] =
      listOfN(provide(nrValues), ("errorCode" | errorCodeCodec) ~ ("length" | uint32L))
        .flatMap[Seq[(Long, ByteVector)]] { errorCodesAndLengths =>
        val errorCodes = errorCodesAndLengths.map(_._1)
        val lengths = errorCodesAndLengths.map(_._2.toInt)

        val valueCodecs = lengths.map(bytes)

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
