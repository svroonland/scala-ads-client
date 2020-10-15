package nl.vroste.adsclient.internal

import nl.vroste.adsclient._
import nl.vroste.adsclient.internal.codecs.AdsSumCommandResponseCodecs
import nl.vroste.adsclient.internal.util.AttemptUtil._
import scodec.Codec
import shapeless.HList
import zio.ZManaged
import zio.clock.Clock
import zio.stream.{ ZSink, ZStream }

private[adsclient] class AdsClientImpl(client: AdsCommandClient) extends AdsClient.Service { self =>
  import AdsClientImpl._
  import AdsCommandClient._
  import AdsResponse._

  // TODO we can probably reuse IndexGroup and IndexOffset variants a bit here
  override def varHandle[T](varName: String, codec: Codec[T]): ZManaged[Clock, AdsClientError, VarHandle[T]] =
    variableHandle(varName).map { handle =>
      new VarHandle[T] {
        override def read: AdsT[T]                                                     = self.read(handle, codec)
        override def write(value: T): AdsT[Unit]                                       = self.write(handle, value, codec)
        override def notifications: ZStream[Clock, AdsClientError, AdsNotification[T]] =
          self.notificationsFor(handle, codec)
        override def sink: ZSink[Clock, AdsClientError, T, T, Unit]                    = consumerFor(handle, codec)
      }
    }

  override def varHandle[T <: HList](
    variables: VariableList[T]
  ): ZManaged[Clock, AdsClientError, VarHandle[T]] = createHandles(variables).map { handles =>
    new VarHandle[T] {
      override def read: AdsT[T]                                                     = self.read(variables, handles)
      override def write(value: T): AdsT[Unit]                                       = self.write(variables, handles, value)
      override def notifications: ZStream[Clock, AdsClientError, AdsNotification[T]] =
        ZStream.die(new NotImplementedError("Not supported "))
      override def sink: ZSink[Clock, AdsClientError, T, T, Unit]                    = self.consumerFor(variables, handles)
    }
  }

  override def varHandle[T](
    indexGroup: Long,
    indexOffset: Long,
    codec: Codec[T]
  ): ZManaged[Clock, AdsClientError, VarHandle[T]] =
    ZManaged.succeed {
      new VarHandle[T] {
        override def read: AdsT[T]                                                     = self.read(indexGroup, indexOffset, codec)
        override def write(value: T): AdsT[Unit]                                       = self.write(indexGroup, indexOffset, value, codec)
        override def notifications: ZStream[Clock, AdsClientError, AdsNotification[T]] =
          notificationsFor(indexGroup, indexOffset, codec)
        override def sink: ZSink[Clock, AdsClientError, T, T, Unit]                    = ??? // TODO
      }
    }

  def read[T](handle: VariableHandle, codec: Codec[T]): AdsT[T] =
    read(IndexGroups.ReadWriteSymValByHandle, indexOffset = handle.value, codec)

  def read[T](indexGroup: Long, indexOffset: Long, codec: Codec[T]): AdsT[T] =
    for {
      data    <- client.read(indexGroup, indexOffset, sizeInBytes(codec))
      decoded <- codec.decodeValue(data).toZio(DecodingError)
    } yield decoded

  def read[T <: HList](command: VariableList[T], handles: Seq[VariableHandle]): AdsT[T] =
    for {
      sumCommand         <- readVariablesCommand(handles.zip(command.sizes)).toZio(EncodingError)
      response           <- runSumCommand(sumCommand)
      errorCodesAndValue <- sumReadResponsePayloadDecoder(command.codec, command.variables.size)
                              .decodeValue(response.data)
                              .toZio(DecodingError)
      _                  <- client.checkErrorCodes(errorCodesAndValue._1)
    } yield errorCodesAndValue._2

  def write[T](handle: VariableHandle, value: T, codec: Codec[T]): AdsT[Unit] =
    codec
      .encode(value)
      .toZio(DecodingError)
      .flatMap(client.writeToVariable(handle, _))

  def write[T](indexGroup: Long, indexOffset: Long, value: T, codec: Codec[T]): AdsT[Unit] =
    codec
      .encode(value)
      .toZio(DecodingError)
      .flatMap(client.write(indexGroup, indexOffset, _))

  def write[T <: HList](command: VariableList[T], handles: Seq[VariableHandle], values: T): AdsT[Unit] =
    for {
      sumCommand <- writeVariablesCommand(handles.zip(command.sizes), command.codec, values).toZio(EncodingError)
      response   <- runSumCommand(sumCommand)
      errorCodes <- adsSumWriteCommandResponseCodec.decodeValue(response.data).map(_.errorCodes).toZio(DecodingError)
      _          <- client.checkErrorCodes(errorCodes)
    } yield ()

  def notificationsFor[T](
    handle: VariableHandle,
    codec: Codec[T]
  ): ZStream[Clock, AdsClientError, AdsNotification[T]] =
    withNotificationHandle(handle, codec)(notificationsForHandle(_, codec))

  def notificationsForHandle[T](
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
                .toZio(DecodingError)
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
  def withNotificationHandle[U](varHandle: VariableHandle, codec: Codec[_])(
    f: NotificationHandle => ZStream[Clock, AdsClientError, U]
  ): ZStream[Clock, AdsClientError, U] =
    withNotificationHandle(IndexGroups.ReadWriteSymValByHandle, varHandle.value, codec)(f)

  def withNotificationHandle[R, U](indexGroup: Long, indexOffset: Long, codec: Codec[_])(
    f: NotificationHandle => ZStream[R, AdsClientError, U]
  ): ZStream[R with Clock, AdsClientError, U] =
    ZStream.unwrapManaged(notificationHandle(indexGroup, indexOffset, codec).map(f))

  private def notificationHandle(
    indexGroup: Long,
    indexOffset: Long,
    codec: Codec[_]
  ): ZManaged[Clock, AdsClientError, NotificationHandle] =
    ZManaged.make(client.getNotificationHandle(indexGroup, indexOffset, sizeInBytes(codec), 0, 100))(
      client.deleteNotificationHandle(_).ignore
    )

  def consumerFor[T](
    handle: VariableHandle,
    codec: Codec[T]
  ): ZSink[Clock, AdsClientError, T, T, Unit] =
    ZSink.foreach(write(handle, _, codec))

  def consumerFor[T <: HList](
    variables: VariableList[T]
  ): ZSink[Clock, AdsClientError, T, T, Unit] =
    ZSink.managed(createHandles(variables))(consumerFor(variables, _))

  def consumerFor[T <: HList](
    variables: VariableList[T],
    handles: Seq[VariableHandle]
  ): ZSink[Clock, AdsClientError, T, T, Unit] =
    ZSink.foreach(write(variables, handles, _))

  private def variableHandle(varName: String) =
    ZManaged.make(client.getVariableHandle(varName))(client.releaseVariableHandle(_).ignore)

  def createHandles[T <: HList](command: VariableList[T]): ZManaged[Clock, AdsClientError, Seq[VariableHandle]] =
    (for {
      sumCommand           <- createVariableHandlesCommand(command.variables).toZio(EncodingError)
      response             <- runSumCommand(sumCommand)
      errorCodesAndHandles <- sumWriteReadResponsePayloadDecoder[VariableHandle](command.variables.size)
                                .decodeValue(response.data)
                                .toZio(DecodingError)
      errorCodes            = errorCodesAndHandles.map(_._1)
      _                    <- client.checkErrorCodes(errorCodes)
    } yield errorCodesAndHandles.map(_._2)).toManaged(releaseHandles(_).ignore)

  private def releaseHandles(handles: Seq[VariableHandle]): AdsT[Unit] =
    for {
      sumCommand <- releaseHandlesCommand(handles).toZio(EncodingError)
      response   <- runSumCommand(sumCommand)
      errorCodes <- adsSumWriteCommandResponseCodec.decodeValue(response.data).map(_.errorCodes).toZio(DecodingError)
      _          <- client.checkErrorCodes(errorCodes)
    } yield ()

  private def runSumCommand(c: AdsSumCommand) =
    c.toAdsCommand.toZio(EncodingError) >>= client.runCommand[AdsWriteReadCommandResponse]
}

object AdsClientImpl extends AdsSumCommandResponseCodecs {

  //  import AdsCommandCodecs.variableHandleCodec

  def sizeInBytes(codec: Codec[_]): Long =
    codec.sizeBound.upperBound.getOrElse(codec.sizeBound.lowerBound) / 8
}
