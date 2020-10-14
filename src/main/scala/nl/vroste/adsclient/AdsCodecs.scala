package nl.vroste.adsclient

import java.time._

import nl.vroste.adsclient.internal.util.CodecUtil._
import scodec.{ Attempt, Codec, Err, SizeBound, codecs => scodecs }

import scala.concurrent.duration.{ FiniteDuration, _ }

/**
 * Codecs for PLC variable types
 *
  * Note that the ADS protocol is little-endian, while that of the JVM is not necessarily
 */
trait AdsCodecs {
  val bool: Codec[Boolean] = scodecs.bool(8)
  val byte: Codec[Byte]    = scodecs.byte
  val word: Codec[Int]     = scodecs.uint16L
  val dword: Codec[Long]   = scodecs.uint32L
  val sint: Codec[Int]     = scodecs.int8L
  val usint: Codec[Int]    = scodecs.uint8L
  val int: Codec[Short]    = scodecs.int16L.xmap(_.toShort, _.toInt)
  val uint: Codec[Int]     = scodecs.uint16L
  val dint: Codec[Int]     = scodecs.int32L
  val udint: Codec[Long]   = scodecs.uint32L
  val real: Codec[Float]   = scodecs.floatL
  val lreal: Codec[Double] = scodecs.doubleL

  val instant: Codec[Instant] = dword.xmap(Instant.ofEpochSecond, _.getEpochSecond)

  private val utc = ZoneOffset.UTC

  val date: Codec[LocalDate] =
    instant.xmap(_.atOffset(utc).toLocalDate, Instant.from)

  val dateAndTime: Codec[LocalDateTime] =
    instant.xmap(_.atOffset(utc).toLocalDateTime, _.atOffset(utc).toInstant)

  val time: Codec[FiniteDuration] = dword.xmapc(_.milliseconds)(_.toMillis)

  private val milliToNano         = 1000000
  val timeOfDay: Codec[LocalTime] =
    dword.xmapc(ms => LocalTime.ofNanoOfDay(ms * milliToNano))(_.toNanoOfDay / milliToNano)

  val defaultMaxStringLength = 80

  /**
   * Codec for a string of default maximum length (80 chars)
   */
  val string: Codec[String]  = stringN(defaultMaxStringLength)
  val wstring: Codec[String] = scodecs.utf8

  /**
   * Codec for a string of a non-default length
   *
    * @return
   */
  def stringN(maxLength: Int): Codec[String] =
    scodecs.cstring
      .narrow[String](
        s =>
          if (s.length <= maxLength)
            Attempt.successful(s)
          else
            Attempt.failure(Err(s"String $s is longer than maximum string length ${maxLength}")),
        identity
      )
      .withSizeBound(SizeBound.exact((maxLength + 1) * 8))

  /**
   * Codec for an array of variables of a fixed length
   *
    * @param length Length of the array
   * @param elementCodec Codec for the individual elements of the array
   */
  def array[T](length: Int, elementCodec: Codec[T]): Codec[List[T]] =
    scodecs
      .listOfN(scodecs.provide(length), elementCodec)
      .withSizeBound(SizeBound.exact(length * elementCodec.sizeBound.lowerBound))
}

object AdsCodecs extends AdsCodecs
