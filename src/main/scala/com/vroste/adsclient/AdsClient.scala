package com.vroste.adsclient

import java.time.Instant

import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import scodec.Codec

/**
  * A reactive (non-blocking) client for ADS servers
  *
  * Variables can be read continuously via Observables and written continuously via Observables
  *
  * Supports reading/writing all primitive types as well as creating codecs for custom data types (case classes)
  */
trait AdsClient {
  // TODO read state, state notifications
  // TODO write control
  // TODO read many at once (sum)
  // TODO write many at once (sum)

  /**
    * Read a variable once
    *
    * @param varName PLC variable name
    * @param codec   Codec for the variable type
    * @tparam T Type of the value that will be decoded
    * @return
    */
  def read[T](varName: String, codec: Codec[T]): Task[T]

  /**
    * Write to a variable once
    *
    * @param varName PLC variable name
    * @param codec Codec between scala value and PLC value
    * @tparam T Type of the value
    * @return
    */
  def write[T](varName: String, value: T, codec: Codec[T]): Task[Unit]

  /**
    * Creates an observable that emits an element whenever the underlying PLC variable's value changes
    *
    * @param varName PLC variable name
    * @param codec Codec between scala value and PLC value
    * @tparam T Type of the value
    * @return
    */
  def notificationsFor[T](varName: String, codec: Codec[T]): Observable[AdsNotification[T]]

  /**
    * Creates a consumer that writes elements to a PLC variable
    *
    * @param varName PLC variable name
    * @param codec Codec between scala value and PLC value
    * @tparam T Type of the value
    * @return
    */
  def consumerFor[T](varName: String, codec: Codec[T]): Consumer[T, Unit]

  /**
    * Closes the underlying connection to the ADS server
    *
    * This will also complete any live observables and consumer tasks with an error. It is recommended
    * to close those in the proper order to avoid such errors.
    */
  def close(): Task[Unit]
}

object AdsClient {

  /**
    * Creates a new AdsClient that is connected to a remote ADS server
    *
    * @param settings ADS connection settings
    */
  def connect(settings: AdsConnectionSettings)(implicit scheduler: Scheduler): Task[AdsClient] = {
    val socketClient = monix.nio.tcp.readWriteAsync(settings.hostname, settings.port, 1024)
    for {
      _ <- socketClient.tcpConsumer
      _ <- socketClient.tcpObservable
    } yield new AdsClientImpl(new AdsCommandClient(settings, socketClient))
  }
}

/**
  * A notification of a change in a variable
  *
  * @param value Reported value of the variable
  * @param timestamp Time as reported by the ADS server
  * @tparam T Type of the value
  */
case class AdsNotification[T](value: T, timestamp: Instant)

case class AdsDeviceInfo(majorVersion: Byte, minorVersion: Byte, versionBuild: Short, deviceName: String)

case class AdsState(value: Short) extends AnyVal

sealed trait AdsTransmissionMode

object AdsTransmissionMode {

  case object OnChange extends AdsTransmissionMode

  case object Cyclic extends AdsTransmissionMode

}

case class VariableHandle(value: Long) extends AnyVal

case class NotificationHandle(value: Long) extends AnyVal
