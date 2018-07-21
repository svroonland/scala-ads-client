package com.vroste.adsclient

import com.vroste.adsclient.internal.{AdsClientImpl, AdsCommandClient}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import scodec.Codec
import shapeless.HList

/**
  * A reactive (non-blocking) client for ADS servers
  *
  * Variables can be read continuously via Observables and written continuously via Observables
  *
  * Supports reading/writing all primitive types as well as creating codecs for custom data types (case classes)
  */
trait AdsClient {
  // TODO write control

  // TODO the methods taking handles are useless if we don't offer create and release handles methods

  /**
    * Read a variable once
    *
    * Creates a handle to the variable, reads using the handle and releases the handle
    *
    * @param varName PLC variable name
    * @param codec   Codec for the variable type
    * @tparam T Type of the value that will be decoded
    * @return
    */
  def read[T](varName: String, codec: Codec[T]): Task[T]

  /**
    * Read a variable using an existing handle
    */
  def read[T](handle: VariableHandle, codec: Codec[T]): Task[T]

  /**
    * Read a list of variables once
    *
    * Creates the variable handles, reads the variables and releases the handles
    *
    * Uses ADS Sum commands to perform these as three operations efficiently
    *
    * @param variables [[VariableList]] describing the variable names and their codecs
    * @tparam T Type of [[HList]] of the values
    * @return HList of the read values
    */
  def read[T <: HList](variables: VariableList[T]): Task[T]

  /**
    * Read a list of variables given a list of existing variable handles
    *
    * Uses ADS Sum commands to perform this operation efficiently
    */
  def read[T <: HList](variables: VariableList[T], handles: Seq[VariableHandle]): Task[T]

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
    * Write to a variable given an existing handle
    */
  def write[T](handle: VariableHandle, value: T, codec: Codec[T]): Task[Unit]

  /**
    * Write to a list of variables once
    *
    * Creates handles for the variables, writes the values and releases the handles
    *
    * Uses ADS Sum commands to perform these three operations efficiently
    */
  def write[T <: HList](variables: VariableList[T], value: T): Task[Unit]

  /**
    * Writes to a list of variables given existing handles
    *
    * @param variables
    * @param handles
    * @param value
    * @tparam T
    * @return
    */
  def write[T <: HList](variables: VariableList[T], handles: Seq[VariableHandle], value: T): Task[Unit]

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
    * Creates a consumer that writes to many PLC variables at once
    *
    * Uses ADS sum commands
    *
    * @param variables
    * @param codec
    * @tparam T
    */
  def consumerFor[T <: HList](variables: VariableList[T], codec: Codec[T]): Consumer[T, Unit]

  /**
    * Notifications of ADS status changes
    */
  def statusChanges: Observable[AdsNotification[AdsState]]

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
