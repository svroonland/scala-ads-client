package nl.vroste.adsclient.internal.codecs

import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit

import scodec.Codec

object WindowsFiletime {
  lazy val timestampZero: Instant = Instant.parse("1601-01-01T00:00:00Z")

  def filetimeToInstant(fileTime: Long): Instant = {
    val duration = Duration.of(fileTime / 10, ChronoUnit.MICROS).plus(fileTime % 10 * 100, ChronoUnit.NANOS)
    timestampZero.plus(duration)
  }

  def instantToFiletime(instant: Instant): Long =
    timestampZero.until(instant, ChronoUnit.NANOS) // Something like this

  val codec: Codec[Instant] = scodec.codecs.longL(64).xmap(filetimeToInstant, instantToFiletime)
}
