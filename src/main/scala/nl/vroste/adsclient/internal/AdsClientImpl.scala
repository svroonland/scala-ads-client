package nl.vroste.adsclient.internal

import nl.vroste.adsclient._
import nl.vroste.adsclient.internal.codecs.AdsSumCommandResponseCodecs
import nl.vroste.adsclient.internal.util.AttemptUtil._
import scodec.Codec
import shapeless.HList
import zio.ZManaged
import zio.clock.Clock
import zio.stream.{ ZSink, ZStream }

class AdsClientImpl(client: AdsCommandClient) extends AdsClient.Service {

  import AdsClientImpl._
  import AdsCommandClient._
  import AdsResponse._

  override def read[T](varName: String, codec: Codec[T]): AdsT[T] =
    variableHandle(varName).use(read(_, codec))

  override def read[T](handle: VariableHandle, codec: Codec[T]): AdsT[T] =
    read(IndexGroups.ReadWriteSymValByHandle, indexOffset = handle.value, codec)

  def read[T](indexGroup: Long, indexOffset: Long, codec: Codec[T]): AdsT[T] =
    for {
      data    <- client.read(indexGroup, indexOffset, sizeInBytes(codec))
      decoded <- codec.decodeValue(data).toTask(DecodingError)
    } yield decoded

  override def read[T <: HList](command: VariableList[T]): AdsT[T] =
    createHandles(command).use(read(command, _))

  override def read[T <: HList](command: VariableList[T], handles: Seq[VariableHandle]): AdsT[T] =
    for {
      sumCommand         <- readVariablesCommand(handles.zip(command.sizes)).toTask(EncodingError)
      response           <- runSumCommand(sumCommand)
      errorCodesAndValue <- sumReadResponsePayloadDecoder(command.codec, command.variables.size)
                              .decodeValue(response.data)
                              .toTask(DecodingError)
      _                  <- client.checkErrorCodes(errorCodesAndValue._1)
    } yield errorCodesAndValue._2

  override def write[T](varName: String, value: T, codec: Codec[T]): AdsT[Unit] =
    variableHandle(varName).use(write(_, value, codec))

  override def write[T](handle: VariableHandle, value: T, codec: Codec[T]): AdsT[Unit] =
    codec
      .encode(value)
      .toTask(DecodingError)
      .flatMap(client.writeToVariable(handle, _))

  override def write[T <: HList](command: VariableList[T], values: T): AdsT[Unit] =
    createHandles(command).use(write(command, _, values))

  override def write[T <: HList](command: VariableList[T], handles: Seq[VariableHandle], values: T): AdsT[Unit] =
    for {
      sumCommand <- writeVariablesCommand(handles.zip(command.sizes), command.codec, values).toTask(EncodingError)
      response   <- runSumCommand(sumCommand)
      errorCodes <- adsSumWriteCommandResponseCodec.decodeValue(response.data).map(_.errorCodes).toTask(DecodingError)
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
  override def notificationsFor[T](
    varName: String,
    codec: Codec[T]
  ): ZStream[Clock, AdsClientError, AdsNotification[T]] =
    withNotificationHandle(varName, codec)(notificationsForHandle(_, codec))

  private def notificationsForHandle[T](
    handle: NotificationHandle,
    codec: Codec[T]
  ): ZStream[Clock, AdsClientError, AdsNotification[T]] =
    ZStream.unwrapManaged {
      for {
        queue <- client.notifications.registerQueue(handle.value)
      } yield ZStream
        .fromQueue(queue)
        .flatMap { sample =>
          ZStream
            .fromEffect(
              codec
                .decodeValue(sample.data)
                .toTask(DecodingError)
            )
            .map(AdsNotification(_, sample.timestamp))
        }
    }

  private def notificationsFor[T](
    indexGroup: Long,
    indexOffset: Long,
    codec: Codec[T]
  ): ZStream[Clock, AdsClientError, AdsNotification[T]] =
    withNotificationHandle(indexGroup, indexOffset, codec)(notificationsForHandle(_, codec))

  /**
   * Takes a function producing an observable for some notification handle and produces an observable that
   * when subscribed creates a notification handle and when unsubscribed or completed deletes the notification handle
   */
  def withNotificationHandle[U](varName: String, codec: Codec[_])(
    f: NotificationHandle => ZStream[Clock, AdsClientError, U]
  ): ZStream[Clock, AdsClientError, U] =
    ZStream.managed(variableHandle(varName)).flatMap { varHandle =>
      withNotificationHandle(IndexGroups.ReadWriteSymValByHandle, varHandle.value, codec)(f)
    }

  def withNotificationHandle[R, U](indexGroup: Long, indexOffset: Long, codec: Codec[_])(
    f: NotificationHandle => ZStream[R, AdsClientError, U]
  ): ZStream[R with Clock, AdsClientError, U] = {
    val acquire = client.getNotificationHandle(indexGroup, indexOffset, sizeInBytes(codec), 0, 100)
    val release = client.deleteNotificationHandle _

    ZStream.bracket(acquire)(release(_).ignore).flatMap(f)
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
  override def consumerFor[T](
    varName: String,
    codec: Codec[T]
  ): ZSink[Clock, AdsClientError, T, T, Unit] =
    ZSink.managed(variableHandle(varName)) { handle =>
      ZSink.drain.contramapM(write(handle, _, codec))
    }

  override def consumerFor[T <: HList](
    variables: VariableList[T],
    codec: Codec[T]
  ): ZSink[Clock, AdsClientError, T, T, Unit] =
    ZSink.managed(createHandles(variables)) { handles =>
      ZSink.drain.contramapM(write(variables, handles, _))
    }

  override def stateChanges: ZStream[Clock, AdsClientError, AdsNotification[AdsState]] =
    notificationsFor(IndexGroups.AdsState, indexOffset = 0, codec = adsStateCodec)

  override def readState: AdsT[AdsState] =
    read(IndexGroups.AdsState, indexOffset = 0, codec = adsStateCodec)

  // TODO should  these be moved to the client?

  private def variableHandle(varName: String) =
    ZManaged.make(client.getVariableHandle(varName))(client.releaseVariableHandle(_).ignore)

  def createHandles[T <: HList](command: VariableList[T]): ZManaged[Clock, AdsClientError, Seq[VariableHandle]] =
    (for {
      sumCommand           <- createVariableHandlesCommand(command.variables).toTask(EncodingError)
      response             <- runSumCommand(sumCommand)
      errorCodesAndHandles <- sumWriteReadResponsePayloadDecoder[VariableHandle](command.variables.size)
                                .decodeValue(response.data)
                                .toTask(DecodingError)
      errorCodes            = errorCodesAndHandles.map(_._1)
      _                    <- client.checkErrorCodes(errorCodes)
    } yield errorCodesAndHandles.map(_._2)).toManaged(releaseHandles(_).ignore)

  private def releaseHandles(handles: Seq[VariableHandle]): AdsT[Unit] =
    for {
      sumCommand <- releaseHandlesCommand(handles).toTask(EncodingError)
      response   <- runSumCommand(sumCommand)
      errorCodes <- adsSumWriteCommandResponseCodec.decodeValue(response.data).map(_.errorCodes).toTask(DecodingError)
      _          <- client.checkErrorCodes(errorCodes)
    } yield ()

  private def runSumCommand(c: AdsSumCommand) =
    c.toAdsCommand.toTask(EncodingError) >>= client.runCommand[AdsWriteReadCommandResponse]
}

object AdsClientImpl extends AdsSumCommandResponseCodecs {

  //  import AdsCommandCodecs.variableHandleCodec

  def sizeInBytes(codec: Codec[_]): Long =
    codec.sizeBound.upperBound.getOrElse(codec.sizeBound.lowerBound) / 8
}
