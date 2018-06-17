package com.vroste.adsclient

import java.time.Instant

import com.vroste.adsclient.codec.{AdsReadable, AdsWritable}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}

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
    * @tparam T Type of the value. An implicit [[AdsReadable]] for this type must be in scope
    * @return
    */
  def read[T: AdsReadable](varName: String): Task[T]

  /**
    * Write to a variable once
    *
    * @param varName PLC variable name
    * @tparam T Type of the value. An implicit [[AdsWritable]] for this type must be in scope
    * @return
    */
  def write[T: AdsWritable](varName: String, value: T): Task[Unit]

  /**
    * Creates an observable that emits an element whenever the underlying PLC variable's value changes
    *
    * @param varName PLC variable name
    * @tparam T Type of the value. An implicit [[AdsReadable]] for this type must be in scope
    * @return
    */
  def notificationsFor[T: AdsReadable](varName: String): Observable[AdsNotification[T]]

  /**
    * Creates a consumer that writes elements to a PLC variable
    *
    * @param varName PLC variable name
    * @tparam T Type of the value. An implicit [[AdsWritable]] for this type must be in scope
    * @return
    */
  def consumerFor[T: AdsWritable](varName: String): Consumer[T, Unit]

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
    } yield new AdsClientImpl(new AdsCommandClient(settings, socketClient))
  }
}

case class AdsNotification[T](value: T, timestamp: Instant)

case class AdsDeviceInfo(majorVersion: Byte, minorVersion: Byte, versionBuild: Short, deviceName: String)

case class AdsState(value: Short) extends AnyVal

sealed trait AdsTransmissionMode {}

object AdsTransmissionMode {

  case object OnChange extends AdsTransmissionMode

  case object Cyclic extends AdsTransmissionMode

}

case class VariableHandle(value: Int) extends AnyVal

case class NotificationHandle(value: Int) extends AnyVal
