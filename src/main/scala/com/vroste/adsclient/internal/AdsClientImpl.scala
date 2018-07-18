package com.vroste.adsclient.internal

import com.vroste.adsclient._
import com.vroste.adsclient.internal.AdsSumCommandResponses.AdsSumWriteCommandResponse
import com.vroste.adsclient.internal.codecs.{AdsResponseCodecs, AdsSumCommandResponseCodecs}
import com.vroste.adsclient.internal.util.AttemptUtil._
import com.vroste.adsclient.internal.util.ConsumerUtil
import monix.eval.Task
import monix.reactive.{Consumer, Observable}
import scodec.bits.BitVector
import scodec.{Codec, Decoder}
import shapeless.HList

class AdsClientImpl(client: AdsCommandClient) extends AdsClient {

  import AdsClientImpl._
  import AdsCommandClient._
  import AdsResponse._

  // For proper shutdown, we need to keep track of any cleanup commands that are pending and need the ADS client
  val resourcesToBeReleased: CountingSemaphore = new CountingSemaphore

  override def read[T](varName: String, codec: Codec[T]): Task[T] =
    withVariableHandle(varName)(read(_, codec))

  override def read[T](handle: VariableHandle, codec: Codec[T]): Task[T] =
    for {
      size <- Task.pure(codec.sizeBound.upperBound.getOrElse(codec.sizeBound.lowerBound) / 8)
      data <- client.readVariable(handle, size)
      decoded <- codec.decode(BitVector(data)).toTask
    } yield decoded.value

  override def read[T <: HList](command: VariableList[T]): Task[T] =
    createHandles(command).bracket(read(command, _))(releaseHandles)

  override def read[T <: HList](command: VariableList[T], handles: Seq[VariableHandle]): Task[T] =
    for {
      sumCommand <- readVariablesCommand(handles.zip(command.sizes)).toTask
      adsCommand <- sumCommand.toAdsCommand.toTask
      response <- client.runCommand[AdsWriteReadCommandResponse](adsCommand)
      errorCodesAndValue <- sumReadResponseDecoder(command.codec, command.variables.size).decodeValue(response.data.toBitVector).toTask
      _ <- client.checkErrorCodes(errorCodesAndValue._1)
    } yield errorCodesAndValue._2

  override def write[T](varName: String, value: T, codec: Codec[T]): Task[Unit] =
    withVariableHandle(varName)(write(_, value, codec))

  override def write[T](handle: VariableHandle, value: T, codec: Codec[T]): Task[Unit] =
    codec.encode(value).toTask
      .map(_.toByteVector)
      .flatMap(client.writeToVariable(handle, _))

  override def write[T <: HList](command: VariableList[T], values: T): Task[Unit] =
    createHandles(command).bracket(write(command, _, values))(releaseHandles)

  override def write[T <: HList](command: VariableList[T], handles: Seq[VariableHandle], values: T): Task[Unit] =
    for {
      sumCommand <- writeVariablesCommand(handles.zip(command.sizes), command.codec, values).toTask
      adsCommand <- sumCommand.toAdsCommand.toTask
      response <- client.runCommand[AdsWriteReadCommandResponse](adsCommand)
      errorCodes <- sumWriteResponseDecoder.decodeValue(response.data.toBitVector).toTask
      _ <- client.checkErrorCodes(errorCodes)
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
    withNotificationHandle(varName, codec) { handle =>
      client.notificationSamples
        .filter(_.handle == handle.value)
        .flatMap { sample =>
          codec.decode(BitVector(sample.data)).toObservable
            .map(decodeResult => AdsNotification(decodeResult.value, sample.timestamp))
        }
    }

  /**
    * Takes a function producing an observable for some notification handle and produces an observable that
    * when subscribed creates a notification handle and when unsubscribed deletes the notification handle
    */
  def withNotificationHandle[U](varName: String, codec: Codec[_])(f: NotificationHandle => Observable[U]): Observable[U] =
    Observable.fromTask {
      val readLength = codec.sizeBound.upperBound.getOrElse(codec.sizeBound.lowerBound) / 8

      withVariableHandle(varName) { varHandle =>
        for {
          notificationHandle <- client.getNotificationHandle(varHandle, readLength, 0, 100) // TODO cycletime
        } yield
          f(notificationHandle).doOnTerminateTask(_ => {
            client.deleteNotificationHandle(notificationHandle).forkAndForget // Important! To allow downstream to cancel the Observable
          })
      }
    }.flatten

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
  override def consumerFor[T](varName: String, codec: Codec[T]): Consumer[T, Unit] = {
    def before = for {
      handle <- client.getVariableHandle(varName)
      _ <- resourcesToBeReleased.increment
    } yield handle

    def after(handle: VariableHandle) = for {
      _ <- client.releaseVariableHandle(handle)
      _ <- resourcesToBeReleased.decrement
    } yield ()

    ConsumerUtil.bracket[T, VariableHandle](before, after) {
      Consumer.foreachTask { case (handle, value) => write(handle, value, codec) }
    }
  }

  override def consumerFor[T <: HList](variables: VariableList[T], codec: Codec[T]): Consumer[T, Unit] = {
    def before = for {
      handle <- createHandles(variables)
      _ <- resourcesToBeReleased.increment
    } yield handle

    def after(handles: Seq[VariableHandle]) = for {
      _ <- releaseHandles(handles)
      _ <- resourcesToBeReleased.decrement
    } yield ()

    ConsumerUtil.bracket[T, Seq[VariableHandle]](before, after) {
      Consumer.foreachTask { case (handles, values) => write(variables, handles, values) }
    }
  }

  /**
    * Creates a task that produces a T based on a function that takes a variable handle
    *
    * The handle is created before the task is executed and released just before the task completes
    */
  private def withVariableHandle[T](varName: String)(block: VariableHandle => Task[T]): Task[T] = {
    val acquire = client.getVariableHandle(varName)
    val release = client.releaseVariableHandle _

    acquire.bracket(varHandle => withResource(block(varHandle)))(release)
  }

  private def withResource[T](t: Task[T]): Task[T] =
    resourcesToBeReleased.increment.bracket(_ => t)(_ => resourcesToBeReleased.decrement)

  /**
    * Closes the socket connection after waiting for any acquired resources to be released
    *
    * @return
    */
  override def close(): Task[Unit] =
    for {
      //      _ <- Task.eval(println("Waiting for outstanding resources to close before closing"))
      _ <- resourcesToBeReleased.awaitZero
      //      _ <- Task.eval(println("Closing client"))
      _ <- client.close()
    } yield ()


  // TODO should  these be moved to the client?

  def createHandles[T <: HList](command: VariableList[T]): Task[Seq[VariableHandle]] =
    for {
      sumCommand <- createVariableHandlesCommand(command.variables).toTask
      adsCommand <- sumCommand.toAdsCommand.toTask
      response <- client.runCommand[AdsWriteReadCommandResponse](adsCommand)
      errorCodesAndHandles <- sumWriteReadResponseDecoder[VariableHandle](command.variables.size).decodeValue(response.data.toBitVector).toTask
      errorCodes = errorCodesAndHandles.map(_._1)
      _ <- client.checkErrorCodes(errorCodes)
    } yield errorCodesAndHandles.map(_._2)

  def releaseHandles(handles: Seq[VariableHandle]): Task[Unit] =
    for {
      sumCommand <- releaseHandlesCommand(handles).toTask
      adsCommand <- sumCommand.toAdsCommand.toTask
      response <- client.runCommand[AdsWriteReadCommandResponse](adsCommand)
      _ <- sumWriteResponseDecoder.decodeValue(response.data.toBitVector).toTask
    } yield ()

}

object AdsClientImpl extends AdsSumCommandResponseCodecs {

  import scodec.codecs.{listOfN, provide}

  def sumWriteReadResponseDecoder[T](nrValues: Int)(implicit decoderT: Decoder[T]): Decoder[Seq[(Long, T)]] =
    adsSumWriteReadCommandResponseDecoder(nrValues)
      .emap { response =>
        val responseDecoders = response.responses.map { r =>
          val decodedData = decoderT.decodeValue(r.data.toBitVector)
          decodedData.map((r.errorCode, _))
        }

        responseDecoders.sequence
      }

  def sumReadResponseDecoder[T](codec: Codec[T], nrValues: Int): Decoder[(List[Long], T)] = {
    val errorCodesCodec = listOfN(provide(nrValues), AdsResponseCodecs.errorCodeCodec)

    (errorCodesCodec ~ codec).asDecoder
  }

  def sumWriteResponseDecoder: Decoder[List[Long]] =
    Decoder[AdsSumWriteCommandResponse]
      .map(_.responses.map(_.errorCode))
}
