package nl.vroste.adsclient.internal

import java.time.Instant

import nl.vroste.adsclient.internal.AdsCommand._
import nl.vroste.adsclient.internal.AdsCommandClient.{ NotificationListeners, ResponseListeners }
import nl.vroste.adsclient.internal.AdsResponse._
import nl.vroste.adsclient.internal.AdsSumCommand.{ AdsSumReadCommand, AdsSumWriteCommand, AdsSumWriteReadCommand }
import nl.vroste.adsclient.internal.codecs.{ AdsCommandCodecs, AmsCodecs }
import nl.vroste.adsclient.internal.util.AttemptUtil._
import nl.vroste.adsclient.{ AdsClientError, _ }
import scodec.bits.BitVector
import scodec.{ Attempt, Codec }
import shapeless.HList
import zio._
import zio.clock.Clock
import zio.stream.ZStream

import scala.reflect.ClassTag

private[adsclient] case class AdsNotificationSampleWithTimestamp(handle: Long, timestamp: Instant, data: BitVector)

private[adsclient] case class VariableHandle(value: Long) extends AnyVal

/**
 * Responsible for encoding and executing single ADS commands and decoding their response
 *
 * Also provides all device notifications as an Stream
 *
 * An inner implementation layer of [[AdsClient]]
 *
 * @param settings
 * @param writeQueue Queue for writing to the ADS server
 * @param invokeId Mutable invokeID counter
 * @param notifications Per-handle queue for notification messages
 * @param responsePromises Per-invokeId promise for a response
 */
final class AdsCommandClient(
  settings: AdsConnectionSettings,
  writeQueue: Queue[Chunk[Byte]],
  invokeId: Ref[Int],
  val notifications: NotificationListeners,
  responsePromises: ResponseListeners
) extends AmsCodecs {

  import AdsCommandClient._
  import nl.vroste.adsclient.internal.codecs.AdsCommandCodecs.variableHandleCodec

  def getVariableHandle(varName: String): AdsT[VariableHandle] =
    getVariableHandleCommand(varName).toZio(EncodingError) >>=
      runCommand[AdsWriteReadCommandResponse] >>=
      (_.decode[VariableHandle].toZio(DecodingError))

  def releaseVariableHandle(handle: VariableHandle): AdsT[Unit] =
    releaseVariableHandleCommand(handle).toZio(EncodingError) >>=
      (runCommand[AdsWriteCommandResponse](_).unit)

  def getNotificationHandle(
    indexGroup: Long,
    indexOffset: Long,
    length: Long,
    maxDelay: Long,
    cycleTime: Long
  ): AdsT[NotificationHandle] =
    runCommand[AdsAddDeviceNotificationCommandResponse] {
      AdsAddDeviceNotificationCommand(
        indexGroup,
        indexOffset,
        length,
        AdsTransmissionMode.OnChange,
        maxDelay,
        cycleTime
      )
    }.map(_.notificationHandle)
      .map(NotificationHandle)

  def deleteNotificationHandle(notificationHandle: NotificationHandle): AdsT[Unit] =
    runCommand[AdsDeleteDeviceNotificationCommandResponse] {
      AdsDeleteDeviceNotificationCommand(notificationHandle.value)
    }.unit

  def write(indexGroup: Long, indexOffset: Long, value: BitVector): AdsT[Unit] =
    runCommand[AdsWriteCommandResponse] {
      AdsWriteCommand(indexGroup, indexOffset, value)
    }.unit

  def read(indexGroup: Long, indexOffset: Long, size: Long): AdsT[BitVector] =
    for {
      command  <- readCommand(indexGroup, indexOffset, size).toZio(EncodingError)
      response <- runCommand[AdsReadCommandResponse](command)
    } yield response.data

  /**
   * Run a command, await the response to the command and return it
   */
  private[adsclient] def runCommand[R <: AdsResponse: ClassTag](command: AdsCommand): AdsT[R] =
    for {
      invokeId <- generateInvokeId
      packet    = createPacket(command, invokeId)
      bytes    <- Codec[AmsPacket].encode(packet).toZio[Clock, AdsClientError](EncodingError)
      _        <- ZIO(println(s"Running command ${command}")).orDie
      response <- responsePromises.registerListener(invokeId).use { p =>
                    writeQueue.offer(Chunk.fromIterable(bytes.toByteArray)) *> awaitResponse(p)
                  }
      _        <- checkResponse(response)
    } yield response

  private def createPacket(command: AdsCommand, invokeId: Int): AmsPacket =
    AmsPacket(
      AmsHeader(
        amsNetIdTarget = settings.amsNetIdTarget,
        amsPortTarget = settings.amsPortTarget,
        amsNetIdSource = settings.amsNetIdSource,
        amsPortSource = settings.amsPortSource,
        commandId = AdsCommand.commandId(command),
        stateFlags = 4, // ADS command
        errorCode = 0,
        invokeId = invokeId,
        data = Left(command)
      )
    )

  private def awaitResponse[R](p: Promise[AdsClientError, AmsPacket])(implicit classTag: ClassTag[R]): AdsT[R] =
    for {
      response <- p.await.timeoutFail(ResponseTimeout)(settings.timeout)
      result   <- response.header.data match {
                    case Right(r) if r.getClass == classTag.runtimeClass =>
                      ZIO.succeed(r.asInstanceOf[R])
                    case r @ _                                           =>
                      ZIO.fail[AdsClientError](UnexpectedResponse)
                  }
    } yield result

  private def checkErrorCode(errorCode: Long): AdsT[Unit] =
    ZIO.fail(AdsErrorResponse(errorCode)).unless(errorCode == 0L)

  def checkErrorCodes(errorCodes: Seq[Long]): AdsT[Unit] =
    ZIO.foreach_(errorCodes)(checkErrorCode)

  private def checkResponse(r: AdsResponse): AdsT[Unit] = checkErrorCode(r.errorCode)

  private val generateInvokeId: UIO[Int] = invokeId.updateAndGet(_ + 1)
}

object AdsCommandClient extends AdsCommandCodecs with AmsCodecs {

  class NotificationListeners(queues: Ref[Map[Long, Queue[AdsNotificationSampleWithTimestamp]]]) {
    def registerQueue(handle: Long): ZManaged[Any, Nothing, Queue[AdsNotificationSampleWithTimestamp]] =
      for {
        queue <- Queue.unbounded[AdsNotificationSampleWithTimestamp].toManaged(_.shutdown)
        _     <- queues
                   .update(_ + (handle -> queue))
                   .toManaged(_ => queues.update(_ - handle))
      } yield queue
  }

  class ResponseListeners(listeners: Ref[Map[Int, Promise[AdsClientError, AmsPacket]]]) {
    def registerListener(invokeId: Int): ZManaged[Any, Nothing, Promise[AdsClientError, AmsPacket]] =
      for {
        promise <- Promise.make[AdsClientError, AmsPacket].toManaged_
        _       <- listeners
                     .update(_ + (invokeId -> promise))
                     .toManaged(_ => listeners.update(_ - invokeId))
      } yield promise
  }

  /**
   * Managed background process that processes incoming data by:
   *
   * 1. Completing promises for expected incoming responses (by invoke ID)
   * 2. Putting incoming notifications in the right queue (by notification handle)
   *
   * @return
   */
  def runLoop(
    inputStream: ZStream[Clock, Exception, Byte]
  ): ZManaged[Clock, Nothing, (ResponseListeners, NotificationListeners)] =
    for {
      substreams <- decodeStream(inputStream)(amsPacketCodec).broadcast(2, 10)

      responsePromises   <- Ref.make[Map[Int, Promise[AdsClientError, AmsPacket]]](Map.empty).toManaged_
      notificationQueues <- Ref.make[Map[Long, Queue[AdsNotificationSampleWithTimestamp]]](Map.empty).toManaged_

      // Completes a promise for each received packet depending on its invokeId
      responseProcessLoop = substreams(0).foreach { packet =>
                              for {
                                promises  <- responsePromises.get
                                promiseOpt = promises.get(packet.header.invokeId)
                                _         <- promiseOpt.map(_.succeed(packet)).getOrElse(ZIO.unit)
                              } yield ()
                            }

      notificationSamples                                    = {
        // Stream of all responses from the ADS server
        val responses: ZStream[Clock, AdsClientError, AdsResponse] =
          substreams(1)
            .map(_.header.data.toSeq)
            .mapConcat(Chunk.fromIterable)

        responses.collect { case r @ AdsNotificationResponse(_) => r }.map { r =>
          for {
            stamp  <- r.stamps
            sample <- stamp.samples
          } yield AdsNotificationSampleWithTimestamp(sample.handle, stamp.timestamp, sample.data)
        }.mapConcat(Chunk.fromIterable)
      }

      // Task that pushes notifications to the queue for the given notification handle
      notificationsToQueue: ZIO[Clock, AdsClientError, Unit] = notificationSamples.foreach { n =>
                                                                 (for {
                                                                   queues <- notificationQueues.get
                                                                   queue  <- ZIO
                                                                               .fromOption(queues.get(n.handle))
                                                                               .orElseFail(UnknownNotificationHandle)
                                                                   _      <- queue.offer(n)
                                                                 } yield ())
                                                                   .tapError(_ =>
                                                                     UIO(
                                                                       println(s"Unknown notification ${n.handle}")
                                                                     )
                                                                   )
                                                                   .orElseSucceed(())
                                                               }

      _ <- (responseProcessLoop <&> notificationsToQueue).fork.toManaged_
    } yield (
      new ResponseListeners(responsePromises),
      new NotificationListeners(notificationQueues)
    )

  def getVariableHandleCommand(varName: String): Attempt[AdsWriteReadCommand] =
    for {
      encodedVarName <- AdsCodecs.string.encode(varName)
    } yield AdsWriteReadCommand(
      indexGroup = IndexGroups.GetSymHandleByName,
      indexOffset = 0x00000000,
      readLength = 4,
      values = encodedVarName
    )

  private def releaseVariableHandleCommand(handle: VariableHandle): Attempt[AdsWriteCommand] =
    for {
      encodedHandle <- Codec[VariableHandle].encode(handle)
    } yield AdsWriteCommand(indexGroup = IndexGroups.ReleaseSymHandle, indexOffset = 0x00000000, values = encodedHandle)

  private def readVariableCommand(handle: VariableHandle, size: Long): Attempt[AdsReadCommand] =
    readCommand(IndexGroups.ReadWriteSymValByHandle, handle.value, size)

  private def readCommand(indexGroup: Long, indexOffset: Long, size: Long): Attempt[AdsReadCommand] =
    Attempt.successful {
      AdsReadCommand(indexGroup, indexOffset, readLength = size)
    }

  private def writeVariableCommand(handle: VariableHandle, value: BitVector): Attempt[AdsWriteCommand] =
    Attempt.successful {
      AdsWriteCommand(IndexGroups.ReadWriteSymValByHandle, indexOffset = handle.value, values = value)
    }

  def createVariableHandlesCommand(variables: Seq[String]): Attempt[AdsSumWriteReadCommand] =
    for {
      encodedVarNames <- variables.map(AdsCodecs.string.encode).sequence
      commands         = encodedVarNames.map(
                           AdsWriteReadCommand(IndexGroups.GetSymHandleByName, indexOffset = 0x00000000, readLength = 4, _)
                         )
    } yield AdsSumWriteReadCommand(commands)

  def readVariablesCommand(handlesAndLengths: Seq[(VariableHandle, Long)]): Attempt[AdsSumReadCommand] =
    for {
      subCommands <- handlesAndLengths.map { case (handle, sizeInBits) => (handle, sizeInBits / 8) }
                       .map((readVariableCommand _).tupled)
                       .sequence
    } yield AdsSumReadCommand(subCommands)

  def writeVariablesCommand[T <: HList](
    handlesAndLengths: Seq[(VariableHandle, Long)],
    codec: Codec[T],
    value: T
  ): Attempt[AdsSumWriteCommand] = {
    val (handles, lengthsInBits) = handlesAndLengths.unzip
    for {
      encodedValue <- codec.encode(value)
      encodedValues = splitBitVectorAtPositions(encodedValue, lengthsInBits.toList)

      subCommands <- handles.zip(encodedValues).map((writeVariableCommand _).tupled).sequence
    } yield AdsSumWriteCommand(subCommands, encodedValue)
  }

  def releaseHandlesCommand(handles: Seq[VariableHandle]): Attempt[AdsSumWriteCommand] =
    for {
      subCommands <- handles.map(releaseVariableHandleCommand).sequence
      values       = BitVector.concat(subCommands.map(_.values))
    } yield AdsSumWriteCommand(subCommands, values)

  private def splitBitVectorAtPositions(bitVector: BitVector, lengthsInBits: List[Long]): List[BitVector] =
    lengthsInBits
      .foldLeft((bitVector, List.empty[BitVector])) { case ((remaining, acc), length) =>
        val (value, newRemaining) = remaining.splitAt(length)
        (newRemaining, acc :+ value)
      }
      ._2

  private def decodeStream[R, S: Codec](stream: ZStream[R, Exception, Byte]): ZStream[R, AdsClientError, S] =
    ZStreamScodecOps.decodeStream(stream.mapError[AdsClientError](AdsClientException(_)), DecodingError.apply)(
      implicitly[Codec[S]]
    )
}
