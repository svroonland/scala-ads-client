package com.vroste.adsclient

import AttemptConversion._
import monix.eval.Task
import monix.reactive.{Consumer, Observable}
import scodec.Codec
import scodec.bits.BitVector
import shapeless.HNil

class AdsClientImpl(client: AdsCommandClient) extends AdsClient {
  // For proper shutdown, we need to keep track of any cleanup commands that are pending and need the ADS client
  val resourcesToBeReleased: CountingSemaphore = new CountingSemaphore

  override def read[T](varName: String, codec: Codec[T]): Task[T] =
    withVariableHandle(varName)(read(_, codec))

  def read[T](handle: VariableHandle, codec: Codec[T]): Task[T] =
    for {
      size <- Task.pure(codec.sizeBound.upperBound.getOrElse(codec.sizeBound.lowerBound) / 8)
      data <- client.readVariable(handle, size)
      decoded <- codec.decode(BitVector(data)).toTask
    } yield decoded.value

  override def write[T](varName: String, value: T, codec: Codec[T]): Task[Unit] =
    withVariableHandle(varName)(write(_, value, codec))

  def write[T](handle: VariableHandle, value: T, codec: Codec[T]): Task[Unit] =
    codec.encode(value).toTask
      .map(_.toByteVector)
      .flatMap(client.writeToVariable(handle, _))

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
      Consumer.foreachTask {
        case (handle, value) =>
          for {
            encoded <- codec.encode(value).toTask
            _ <- client.writeToVariable(handle, encoded.toByteVector)
          } yield ()
      }
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
    * @return
    */
  override def close(): Task[Unit] =
    for {
      //      _ <- Task.eval(println("Waiting for outstanding resources to close before closing"))
      _ <- resourcesToBeReleased.awaitZero
      //      _ <- Task.eval(println("Closing client"))
      _ <- client.close()
    } yield ()

  override def readMany[T](varNameT: String, codecT: Codec[T]): ReadManyBuilder[T :: HNil] = new ReadManyBuilderImpl[T :: HNil] {
    override def and[U](varName: String, codec: Codec[U]): ReadManyBuilder[U :: T :: HNil] = ???

    override def read: Task[T :: HNil] = ???
  }
}
