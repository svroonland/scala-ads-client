package com.vroste.adsclient.internal.codecs

import com.vroste.adsclient.internal.AdsSumCommand
import scodec.Codec

trait AdsSumCommandCodecs {
  import AdsSumCommand._
  import scodec.codecs._

  implicit val sumReadWriteRequestPartCodec: Codec[SumReadWriteRequestPart] =
    (uint32L :: uint32L :: uint32L :: uint32L).as[SumReadWriteRequestPart]

  implicit val sumWriteRequestPartCodec: Codec[SumWriteRequestPart] =
    (uint32L :: uint32L :: uint32L).as[SumWriteRequestPart]

  implicit val sumReadRequestPartCodec: Codec[SumReadRequestPart] =
    (uint32L :: uint32L :: uint32L).as[SumReadRequestPart]

}
