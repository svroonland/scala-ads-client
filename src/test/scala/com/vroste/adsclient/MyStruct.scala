package com.vroste.adsclient

import scodec.Codec
import AdsCodecs._

case class MyStruct(myInt: Short, myBool: Boolean)

object MyStruct {
  implicit val codec: Codec[MyStruct] = (int :: bool).as
}