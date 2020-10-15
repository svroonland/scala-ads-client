package nl.vroste.adsclient

import nl.vroste.adsclient.internal.codecs.AdsCommandCodecs
import nl.vroste.adsclient.internal.{ AdsClientImpl, AdsCommandClient, IndexGroups, SocketClient }
import scodec.Codec
import shapeless.HList
import zio._
import zio.clock.Clock
import zio.stream.{ ZSink, ZStream }

/**
 * Represents a handle to a variable that can be used for reading, writing and change notification streaming
 *
 * @tparam T Scala type of the variable
 */
trait VarHandle[T] {

  /**
   * Read the value of the variable(s)
   */
  def read: AdsT[T]

  /**
   * Write to the variable(s)
   * @param value
   */
  def write(value: T): AdsT[Unit]

  /**
   * Creates a stream that emits an element whenever the underlying PLC variable's value changes
   */
  def notifications: ZStream[Clock, AdsClientError, AdsNotification[T]]

  /**
   * Creates a Sink that writes incoming elements to the PLC variable(s)
   */
  def sink: ZSink[Clock, AdsClientError, T, T, Unit]
}

/**
 * A reactive (non-blocking) client for ADS servers
 *
 * Variables can be read continuously via ZStream and written continuously via Observables
 *
 * Supports reading/writing all primitive types as well as creating codecs for custom data types (case classes)
 */
object AdsClient {
  trait Service {
    // TODO write control

    /**
     * Create a handle to a single PLC variable for reading and writing
     *
     * @param varName Path to the variable, eg "MAIN.var1"
     * @param codec Codec for the variable
     * @tparam T Scala type of the variable
     * @return Managed resource that can be used for reading, writing and notifications
     */
    def varHandle[T](varName: String, codec: Codec[T]): ZManaged[Clock, AdsClientError, VarHandle[T]]

    /**
     * Create a handle to many PLC variables for reading and writing
     *
     * Uses ADS Sum commands for efficient reading and writing of many variables at once
     *
     * @param variables List of variable names and codecs
     * @tparam T Scala type of the variable list
     * @return Managed resource that can be used for reading, writing and notifications
     */
    def varHandle[T <: HList](variables: VariableList[T]): ZManaged[Clock, AdsClientError, VarHandle[T]]

    /**
     * Create a handle to an index group and offset for reading and writing
     *
     * @param indexGroup
     * @param indexOffset
     * @param codec
     * @tparam T
     */
    def varHandle[T](
      indexGroup: Long,
      indexOffset: Long,
      codec: Codec[T]
    ): ZManaged[Clock, AdsClientError, VarHandle[T]]

    /**
     * Handle to the ADS State
     */
    val adsState: ZManaged[Clock, AdsClientError, VarHandle[AdsState]] =
      varHandle(IndexGroups.AdsState, indexOffset = 0, codec = AdsCommandCodecs.adsStateCodec)
  }

//  // Accessors
//  def read[T](varName: String, codec: Codec[T]): ZIO[Clock with AdsClient, AdsClientError, T] =
//    withService(_.read(varName, codec))
//
//  def read[T](handle: VariableHandle, codec: Codec[T]): ZIO[Clock with AdsClient, AdsClientError, T] =
//    withService(_.read(handle, codec))
//
//  def read[T <: HList](variables: VariableList[T]): ZIO[Clock with AdsClient, AdsClientError, T] =
//    withService(_.read(variables))
//
//  def read[T <: HList](
//    variables: VariableList[T],
//    handles: Seq[VariableHandle]
//  ): ZIO[Clock with AdsClient, AdsClientError, T] = withService(_.read(variables, handles))
//
//  def write[T](varName: String, value: T, codec: Codec[T]): ZIO[Clock with AdsClient, AdsClientError, Unit] =
//    withService(_.write(varName, value, codec))
//
//  def write[T](handle: VariableHandle, value: T, codec: Codec[T]): ZIO[Clock with AdsClient, AdsClientError, Unit] =
//    withService(_.write(handle, value, codec))
//
//  def write[T <: HList](variables: VariableList[T], value: T): ZIO[Clock with AdsClient, AdsClientError, Unit] =
//    withService(_.write(variables, value))
//
//  def write[T <: HList](
//    variables: VariableList[T],
//    handles: Seq[VariableHandle],
//    value: T
//  ): ZIO[Clock with AdsClient, AdsClientError, Unit] =
//    withService(_.write(variables, handles, value))
//
  def notificationsFor[T](
    varName: String,
    codec: Codec[T]
  ): ZStream[Clock with Has[Service], AdsClientError, AdsNotification[T]] =
    ZStream.unwrapManaged(varHandle(varName, codec).map(_.notifications))
//
//  def consumerFor[T](
//    varName: String,
//    codec: Codec[T]
//  ): ZSink[Clock with AdsClient, AdsClientError, T, T, Unit] =
//    ZSink.managed[Clock with AdsClient, AdsClientError, T, Service, T, Unit](ZManaged.service[Service])(
//      _.consumerFor(varName, codec)
//    )
//
//  def consumerFor[T <: HList](
//    variables: VariableList[T],
//    codec: Codec[T]
//  ): ZSink[Clock with AdsClient, AdsClientError, T, T, Unit] =
//    ZSink.managed[Clock with AdsClient, AdsClientError, T, Service, T, Unit](ZManaged.service[Service])(
//      _.consumerFor(variables, codec)
//    )

  def varHandle[T](varName: String, codec: Codec[T]): ZManaged[AdsClient with Clock, AdsClientError, VarHandle[T]]    =
    withService(_.varHandle(varName, codec))
  def varHandle[T <: HList](variables: VariableList[T]): ZManaged[AdsClient with Clock, AdsClientError, VarHandle[T]] =
    withService(_.varHandle(variables))
  // TODO handle for indexGroups

  private def withService[R, E, A](f: AdsClient.Service => ZManaged[R, E, A]): ZManaged[R with AdsClient, E, A] =
    ZManaged.service[Service] >>= f

  /**
   * Creates a new AdsClient that is connected to a remote ADS server
   *
   * @param settings ADS connection settings
   * @return An ADS client that is cleaned up automatically after use
   */
  def connect(settings: AdsConnectionSettings): ZManaged[Clock, Exception, AdsClient.Service] =
    for {
      things                                    <- SocketClient.make(settings.hostname, settings.port, settings.timeout)
      (inputStream, writeQueue)                  = things
      runLoopThings                             <- AdsCommandClient.runLoop(inputStream)
      (responseListeners, notificationListeners) = runLoopThings
      invokeIdRef                               <- Ref.make(0).toManaged_
    } yield new AdsClientImpl(
      new AdsCommandClient(settings, writeQueue, invokeIdRef, notificationListeners, responseListeners)
    )

  val live: ZLayer[Clock with Has[AdsConnectionSettings], Exception, Has[Service]] =
    ZLayer.fromServiceManaged[AdsConnectionSettings, Clock, Exception, Service](connect)
}
