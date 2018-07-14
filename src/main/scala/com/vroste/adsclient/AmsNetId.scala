package com.vroste.adsclient

case class AmsNetId(value: String) extends AnyVal {
  def asString: String = value
}

object AmsNetId {
  def fromString(value: String): AmsNetId = AmsNetId(value)
}