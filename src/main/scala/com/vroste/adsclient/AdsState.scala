package com.vroste.adsclient

import enumeratum._

sealed abstract class AdsState(value: Short) extends EnumEntry

object AdsState extends Enum[AdsState]{
  override def values = findValues

  case object Invalid extends AdsState(0)
  case object Idle extends AdsState(1)
  case object Reset extends AdsState(2)
  case object Init extends AdsState(3)
  case object Start extends AdsState(4)
  case object Run extends AdsState(5)
  case object Stop extends AdsState(6)
  case object SaveConfiguration extends AdsState(7)
  case object LoadConfiguration extends AdsState(8)
  case object PowerFailure extends AdsState(9)
  case object PowerGood extends AdsState(10)
  case object Error extends AdsState(11)
  case object Shutdown extends AdsState(12)
  case object Suspend extends AdsState(13)
  case object Resume extends AdsState(14)
  case object Config extends AdsState(15)
  case object Reconfig extends AdsState(16)

}