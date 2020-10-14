package nl.vroste.adsclient.internal

import monix.eval.Task
import monix.reactive.{ Consumer, Observable }
import nl.vroste.adsclient._
import nl.vroste.adsclient.internal.codecs.AdsSumCommandResponseCodecs
import nl.vroste.adsclient.internal.util.AttemptUtil._
import nl.vroste.adsclient.internal.util.{ ConsumerUtil, ObservableUtil }
import scodec.Codec
import shapeless.HList

class AdsClientImpl(client: AdsCommandClient) extends AdsClient {

  import AdsClientImpl._
  import AdsCommandClient._
  import AdsResponse._

  // For proper shutdown, we need to keep track of any cleanup commands that are pending and need the ADS client
  val resourcesInUse: CountingSemaphore = new CountingSemaphore

  override def read[T](varName: String, codec: Codec[T]): Task[T] =
    withVariableHandle(varName)(read(_, codec))

  override def read[T](handle: VariableHandle, codec: Codec[T]): Task[T] =
    read(IndexGroups.ReadWriteSymValByHandle, indexOffset = handle.value, codec)

  def read[T](indexGroup: Long, indexOffset: Long, codec: Codec[T]): Task[T] =
    for {
      data    <- client.read(indexGroup, indexOffset, sizeInBytes(codec))
      decoded <- codec.decodeValue(data).toTask
    } yield decoded

  override def read[T <: HList](command: VariableList[T]): Task[T] =
    createHandles(command).bracket(read(command, _))(releaseHandles)

  override def read[T <: HList](command: VariableList[T], handles: Seq[VariableHandle]): Task[T] =
    for {
      sumCommand         <- readVariablesCommand(handles.zip(command.sizes)).toTask
      adsCommand         <- sumCommand.toAdsCommand.toTask
      response           <- client.runCommand[AdsWriteReadCommandResponse](adsCommand)
      errorCodesAndValue <- sumReadResponsePayloadDecoder(command.codec, command.variables.size)
                              .decodeValue(response.data)
                              .toTask
      _                  <- client.checkErrorCodes(errorCodesAndValue._1)
    } yield errorCodesAndValue._2

  override def write[T](varName: String, value: T, codec: Codec[T]): Task[Unit] =
    withVariableHandle(varName)(write(_, value, codec))

  override def write[T](handle: VariableHandle, value: T, codec: Codec[T]): Task[Unit] =
    codec
      .encode(value)
      .toTask
      .flatMap(client.writeToVariable(handle, _))

  override def write[T <: HList](command: VariableList[T], values: T): Task[Unit] =
    createHandles(command).bracket(write(command, _, values))(releaseHandles)

  override def write[T <: HList](command: VariableList[T], handles: Seq[VariableHandle], values: T): Task[Unit] =
    for {
      sumCommand <- writeVariablesCommand(handles.zip(command.sizes), command.codec, values).toTask
      adsCommand <- sumCommand.toAdsCommand.toTask
      response   <- client.runCommand[AdsWriteReadCommandResponse](adsCommand)
      errorCodes <- adsSumWriteCommandResponseCodec.decodeValue(response.data).map(_.errorCodes).toTask
      _          <- client.checkErrorCodes(errorCodes)
    } yield ()

  /**
   * Creates an observable that emits whenever an ADS notification is received
   *
    * A symbol handle and device notification are created and cleaned up when the observable terminates.
   *
    * @param varName PLC variable name
   * @param codec   Codec between scala value and PLC value
   * @tparam T Type of the value
   * @return
   */
  override def notificationsFor[T](varName: String, codec: Codec[T]): Observable[AdsNotification[T]] =
    withNotificationHandle(varName, codec)(notificationsForHandle(_, codec))

  private def notificationsForHandle[T](handle: NotificationHandle, codec: Codec[T]): Observable[AdsNotification[T]] =
    client.notificationSamples
      .filter(_.handle == handle.value)
      .flatMap { sample =>
        codec
          .decodeValue(sample.data)
          .toObservable
          .map(AdsNotification(_, sample.timestamp))
      }

  private def notificationsFor[T](
    indexGroup: Long,
    indexOffset: Long,
    codec: Codec[T]
  ): Observable[AdsNotification[T]] =
    withNotificationHandle(indexGroup, indexOffset, codec)(notificationsForHandle(_, codec))

  /**
   * Takes a function producing an observable for some notification handle and produces an observable that
   * when subscribed creates a notification handle and when unsubscribed or completed deletes the notification handle
   */
  def withNotificationHandle[U](varName: String, codec: Codec[_])(
    f: NotificationHandle => Observable[U]
  ): Observable[U] = {
    val acquire = acquireResource(client.getVariableHandle(varName))
    val release = client.releaseVariableHandle _ andThen releaseResource

    ObservableUtil.bracket(acquire) { varHandle =>
      withNotificationHandle(IndexGroups.ReadWriteSymValByHandle, varHandle.value, codec)(f)
    }(release)
  }

  def withNotificationHandle[U](indexGroup: Long, indexOffset: Long, codec: Codec[_])(
    f: NotificationHandle => Observable[U]
  ): Observable[U] = {
    val acquire = acquireResource(client.getNotificationHandle(indexGroup, indexOffset, sizeInBytes(codec), 0, 100))
    val release = client.deleteNotificationHandle _ andThen releaseResource

    ObservableUtil.bracket(acquire)(f)(release)
  }

  /**
   * Creates a consumer that writes elements to a PLC variable
   *
    * A symbol handle is created when the first value is consumed and cleaned up when
   * there are no more values to consume.
   *
    * @param varName PLC variable name
   * @param codec   Codec between scala value and PLC value
   * @tparam T Type of the value
   * @return
   */
  override def consumerFor[T](varName: String, codec: Codec[T]): Consumer[T, Unit] =
    ConsumerUtil.bracket[T, VariableHandle](
      acquire = acquireResource(client.getVariableHandle(varName)),
      release = client.releaseVariableHandle _ andThen releaseResource
    ) {
      Consumer.foreachTask { case (handle, value) => write(handle, value, codec) }
    }

  override def consumerFor[T <: HList](variables: VariableList[T], codec: Codec[T]): Consumer[T, Unit] =
    ConsumerUtil.bracket[T, Seq[VariableHandle]](
      acquire = acquireResource(createHandles(variables)),
      release = releaseHandles _ andThen releaseResource
    ) {
      Consumer.foreachTask { case (handles, values) => write(variables, handles, values) }
    }

  /**
   * Creates a task that produces a T based on a function that takes a variable handle
   *
    * The handle is created before the task is executed and released just before the task completes
   */
  private def withVariableHandle[T](varName: String)(block: VariableHandle => Task[T]): Task[T] = {
    val acquire = acquireResource(client.getVariableHandle(varName))
    val release = client.releaseVariableHandle _ andThen releaseResource

    acquire.bracket(block)(release)
  }

  override def stateChanges: Observable[AdsNotification[AdsState]] =
    notificationsFor(IndexGroups.AdsState, indexOffset = 0, codec = adsStateCodec)

  override def readState: Task[AdsState] =
    read(IndexGroups.AdsState, indexOffset = 0, codec = adsStateCodec)

  /**
   * Closes the socket connection after waiting for any acquired resources to be released
   *
    * @return
   */
  override def close(): Task[Unit] =
    for {
      _ <- resourcesInUse.awaitZero
      _ <- client.close()
    } yield ()

  // TODO should  these be moved to the client?

  def createHandles[T <: HList](command: VariableList[T]): Task[Seq[VariableHandle]] =
    for {
      sumCommand           <- createVariableHandlesCommand(command.variables).toTask
      adsCommand           <- sumCommand.toAdsCommand.toTask
      response             <- client.runCommand[AdsWriteReadCommandResponse](adsCommand)
      errorCodesAndHandles <- sumWriteReadResponsePayloadDecoder[VariableHandle](command.variables.size)
                                .decodeValue(response.data)
                                .toTask
      errorCodes            = errorCodesAndHandles.map(_._1)
      _                    <- client.checkErrorCodes(errorCodes)
    } yield errorCodesAndHandles.map(_._2)

  def releaseHandles(handles: Seq[VariableHandle]): Task[Unit] =
    for {
      sumCommand <- releaseHandlesCommand(handles).toTask
      adsCommand <- sumCommand.toAdsCommand.toTask
      response   <- client.runCommand[AdsWriteReadCommandResponse](adsCommand)
      errorCodes <- adsSumWriteCommandResponseCodec.decodeValue(response.data).map(_.errorCodes).toTask
      _          <- client.checkErrorCodes(errorCodes)
    } yield ()

  private def acquireResource[T](t: Task[T]): Task[T] =
    t.flatMap(r => resourcesInUse.increment.map(_ => r))

  private def releaseResource[T](t: Task[T]): Task[T] =
    t.flatMap(r => resourcesInUse.decrement.map(_ => r))
}

object AdsClientImpl extends AdsSumCommandResponseCodecs {

//  import AdsCommandCodecs.variableHandleCodec

  def sizeInBytes(codec: Codec[_]): Long =
    codec.sizeBound.upperBound.getOrElse(codec.sizeBound.lowerBound) / 8
}
