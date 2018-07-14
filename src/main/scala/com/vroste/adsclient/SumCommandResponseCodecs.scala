package com.vroste.adsclient

import scodec.Codec
import scodec.codecs.list

trait SumCommandResponseCodecs extends AdsResponseCodecs {
  import SumCommandResponses._

  implicit val adsSumWriteReadCommandResponseCodec: Codec[AdsSumWriteReadCommandResponse] =
    list(adsWriteReadCommandResponseCodec).xmap(AdsSumWriteReadCommandResponse, _.responses)

  implicit val adsSumWriteCommandResponseCodec: Codec[AdsSumWriteCommandResponse] =
    list(adsWriteCommandResponseCodec).xmap(AdsSumWriteCommandResponse, _.responses)

  implicit val adsSumReadCommandResponseCodec: Codec[AdsSumReadCommandResponse] =
    list(adsReadCommandResponseCodec).xmap(AdsSumReadCommandResponse, _.responses)
}
