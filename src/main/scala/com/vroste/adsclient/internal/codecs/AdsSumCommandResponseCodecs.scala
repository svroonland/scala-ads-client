package com.vroste.adsclient.internal.codecs

import scodec.Codec
import scodec.codecs.list

trait AdsSumCommandResponseCodecs extends AdsResponseCodecs {
  import com.vroste.adsclient.internal.AdsSumCommandResponses._

  // TODO this is wrong, the results are interleaved
  implicit val adsSumWriteReadCommandResponseCodec: Codec[AdsSumWriteReadCommandResponse] =
    list(adsWriteReadCommandResponseCodec).xmap(AdsSumWriteReadCommandResponse, _.responses)

  // TODO this is wrong, the results are interleaved
  implicit val adsSumWriteCommandResponseCodec: Codec[AdsSumWriteCommandResponse] =
    list(adsWriteCommandResponseCodec).xmap(AdsSumWriteCommandResponse, _.responses)

  // TODO this is wrong, the results are interleaved
  implicit val adsSumReadCommandResponseCodec: Codec[AdsSumReadCommandResponse] =
    list(adsReadCommandResponseCodec).xmap(AdsSumReadCommandResponse, _.responses)
}
