package com.vroste.adsclient

import com.vroste.adsclient.AdsCommandClient.decodeAttemptToTask
import monix.eval.Task
import monix.execution.Cancelable
import monix.reactive.{Consumer, Observable}
import scodec.Codec
import scodec.bits.BitVector

class AdsClientImpl(client: AdsCommandClient) extends AdsClient {
  // For proper shutdown, we need to keep track of any cleanup commands that are pending and need the ADS client
  val resourcesToBeReleased: CountingSemaphore = new CountingSemaphore

  override def read[T](varName: String, codec: Codec[T]): Task[T] = {
    for {
      varHandle <- client.getVariableHandle(varName)
      _ <- resourcesToBeReleased.increment
      data <- client
        .readVariable(varHandle, codec.sizeBound.upperBound.getOrElse(codec.sizeBound.lowerBound))
        .doOnFinish { _ =>
          client.releaseVariableHandle(varHandle)
        }
      _ <- resourcesToBeReleased.decrement
      decoded <- decodeAttemptToTask(codec.decode(BitVector(data)))
    } yield decoded.value
  }

  override def write[T](varName: String, value: T, codec: Codec[T]): Task[Unit] = {
    for {
      varHandle <- client.getVariableHandle(varName)
      _ <- resourcesToBeReleased.increment
      encoded <- decodeAttemptToTask(codec.encode(value))
      _ <- client
        .writeToVariable(varHandle, encoded.toByteVector)
        .doOnFinish { _ =>
          client.releaseVariableHandle(varHandle)
        }
      _ <- resourcesToBeReleased.decrement
    } yield ()
  }

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
  override def notificationsFor[T](varName: String, codec: Codec[T]): Observable[AdsNotification[T]] = {
    // Ensures that created variable and notification handles are cleaned up
    def usingNotificationHandle[U](f: NotificationHandle => Observable[U]): Observable[U] = {
      Observable.fromTask {
        for {
          varHandle <- client.getVariableHandle(varName)
                    _ <- resourcesToBeReleased.increment
          readLength = codec.sizeBound.upperBound.getOrElse(codec.sizeBound.lowerBound)
          notificationHandle <- client.getNotificationHandle(varHandle, readLength, 0, 100) // TODO cycletime
        } yield
          f(notificationHandle)
            .doOnTerminateTask(_ => {
              (for {
                _ <- client.deleteNotificationHandle(notificationHandle)
                _ <- client.releaseVariableHandle(varHandle)
                _ <- resourcesToBeReleased.decrement
              } yield ()
                ).forkAndForget // Important
            })
      }.flatten
    }

    usingNotificationHandle { handle =>
      client.notificationSamples
        .filter(_.handle == handle.value)
        .flatMap { sample =>
          Observable.fromTask {
            for {
              decodeResult <- decodeAttemptToTask(codec.decode(BitVector(sample.data)))
            } yield AdsNotification(decodeResult.value, sample.timestamp)
          }
        }
    }
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
  override def consumerFor[T](varName: String, codec: Codec[T]): Consumer[T, Unit] = {
    def before = for {
      handle <- client.getVariableHandle(varName)
      _ <- resourcesToBeReleased.increment
    } yield handle

    def after(handle: VariableHandle) = for {
      _ <- client.releaseVariableHandle(handle)
      _ <- resourcesToBeReleased.decrement
    } yield ()

    consumerBracket[T, VariableHandle](before, after) {
      Consumer.foreachTask {
        case (handle, value) =>
          for {
            encoded <- decodeAttemptToTask(codec.encode(value))
            _ <- client.writeToVariable(handle, encoded.toByteVector)
          } yield ()
      }
    }
  }

  /**
    * Creates a consumer that creates a resource before handling the first element and cleans it up after
    * handling the last element
    *
    * @param acquire Create a resource asychronously
    * @param release Cleanup the resource after the last
    * @param inner   Consumer for tuples of the resource and a value
    * @tparam T Type of elements to consume
    * @tparam R Type of the resource
    * @return A consumer of elements of type T, when executed will result in a value of type Unit
    */
  def consumerBracket[T, R](acquire: Task[R], release: R => Task[Unit])(
    inner: Consumer[(R, T), Unit]): Consumer[T, Unit] = {
    val resourceT = acquire.memoize

    inner
      .transformInput[T] {
      _.mapTask(t => resourceT.map((_, t)))
    }
      .mapTask { _ =>
        resourceT.flatMap(release)
      }
  }

  override def close(): Task[Unit] =
    for {
//      _ <- Task.eval(println("Waiting for outstanding resources to close before closing"))
      _ <- resourcesToBeReleased.awaitZero
//      _ <- Task.eval(println("Closing client"))
      _ <- client.close()
    } yield ()
}
