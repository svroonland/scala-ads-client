package nl.vroste.adsclient

import java.util.concurrent.TimeoutException

import nl.vroste.adsclient.internal.{ AdsClientImpl, AdsCommandClient }
import scodec.Codec
import shapeless.HList
import zio._
import zio.clock.Clock
import zio.nio.channels.AsynchronousSocketChannel
import zio.nio.core.SocketAddress
import zio.stream.{ ZSink, ZStream }

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
  def read[T](varName: String, codec: Codec[T]): AdsT[T]

  /**
   * Read a variable using an existing handle
   */
  def read[T](handle: VariableHandle, codec: Codec[T]): AdsT[T]

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
  def read[T <: HList](variables: VariableList[T]): AdsT[T]

  /**
   * Read a list of variables given a list of existing variable handles
   *
   * Uses ADS Sum commands to perform this operation efficiently
   */
  def read[T <: HList](variables: VariableList[T], handles: Seq[VariableHandle]): AdsT[T]

  /**
   * Write to a variable once
   *
   * @param varName PLC variable name
   * @param codec   Codec between scala value and PLC value
   * @tparam T Type of the value
   * @return
   */
  def write[T](varName: String, value: T, codec: Codec[T]): AdsT[Unit]

  /**
   * Write to a variable given an existing handle
   */
  def write[T](handle: VariableHandle, value: T, codec: Codec[T]): AdsT[Unit]

  /**
   * Write to a list of variables once
   *
   * Creates handles for the variables, writes the values and releases the handles
   *
   * Uses ADS Sum commands to perform these three operations efficiently
   */
  def write[T <: HList](variables: VariableList[T], value: T): AdsT[Unit]

  /**
   * Writes to a list of variables given existing handles
   *
   * @param variables
   * @param handles
   * @param value
   * @tparam T
   * @return
   */
  def write[T <: HList](variables: VariableList[T], handles: Seq[VariableHandle], value: T): AdsT[Unit]

  /**
   * Creates an observable that emits an element whenever the underlying PLC variable's value changes
   *
   * @param varName PLC variable name
   * @param codec   Codec between scala value and PLC value
   * @tparam T Type of the value
   * @return
   */
  def notificationsFor[T](varName: String, codec: Codec[T]): ZStream[Clock, AdsClientError, AdsNotification[T]]

  /**
   * Creates a consumer that writes elements to a PLC variable
   *
   * @param varName PLC variable name
   * @param codec   Codec between scala value and PLC value
   * @tparam T Type of the value
   * @return
   */
  def consumerFor[T](
    varName: String,
    codec: Codec[T]
  ): ZSink[Clock, AdsClientError, T, T, Unit]

  /**
   * Creates a consumer that writes to many PLC variables at once
   *
   * Uses ADS sum commands
   *
   * @param variables
   * @param codec
   * @tparam T
   */
  def consumerFor[T <: HList](
    variables: VariableList[T],
    codec: Codec[T]
  ): ZSink[Clock, AdsClientError, T, T, Unit]

  /**
   * Read the ADS state
   */
  def readState: AdsT[AdsState]

  /**
   * Notifications of ADS state changes
   */
  def stateChanges: ZStream[Clock, AdsClientError, AdsNotification[AdsState]]
}

object AdsClient {

  /**
   * Creates a new AdsClient that is connected to a remote ADS server
   *
   * @param settings ADS connection settings
   * @return An ADS client that is cleaned up automatically after use
   */
  def connect(settings: AdsConnectionSettings): ZManaged[Clock, Exception, AdsClient] =
    for {
      channel                                   <- AsynchronousSocketChannel()
      inetAddress                               <- SocketAddress.inetSocketAddress(settings.hostname, settings.port).toManaged_
      _                                         <- channel
             .connect(inetAddress)
             .timeoutFail(new TimeoutException("Timeout connecting to ADS server"))(settings.timeout)
             .toManaged_
      writeQueue                                <- Queue.bounded[Chunk[Byte]](writeQueueSize).toManaged(_.shutdown)
      inputStream                                = createInputStream(channel)
      runLoopThings                             <- AdsCommandClient.runLoop(inputStream)
      (responseListeners, notificationListeners) = runLoopThings
      _                                         <- writeLoop(channel, writeQueue)
      invokeIdRef                               <- Ref.make(0).toManaged_
    } yield new AdsClientImpl(
      new AdsCommandClient(settings, writeQueue, invokeIdRef, notificationListeners, responseListeners)
    )

  private def writeLoop(
    channel: AsynchronousSocketChannel,
    queue: Queue[Chunk[Byte]]
  ): ZManaged[Any, Nothing, Fiber.Runtime[Exception, Nothing]] =
    (for {
      bytes <- queue.take
      _     <- channel.writeChunk(bytes)
    } yield ()).forever.toManaged_.fork

  val maxFrameSize   = 1024
  val writeQueueSize = 10

  private def createInputStream(channel: AsynchronousSocketChannel): ZStream[Clock, Exception, Chunk[Byte]] =
    ZStream.fromEffect(channel.readChunk(maxFrameSize).retry(Schedule.forever)).forever
}
