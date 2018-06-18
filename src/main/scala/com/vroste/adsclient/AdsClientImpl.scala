package com.vroste.adsclient

import com.vroste.adsclient.AdsCommandClient.attemptToTask
import monix.eval.Task
import monix.reactive.{Consumer, Observable}
import scodec.Codec
import scodec.bits.BitVector

class AdsClientImpl(client: AdsCommandClient) extends AdsClient {

  override def read[T](varName: String, codec: Codec[T]): Task[T] = {
    for {
      _ <- Task.eval(println("Getting variable handle"))
      varHandle <- client.getVariableHandle(varName)
      _ <- Task.eval(println(s"Got variable handle ${varHandle}"))
      data <- client
        .readVariable(varHandle, codec.sizeBound.upperBound.getOrElse(codec.sizeBound.lowerBound))
        .doOnFinish { _ =>
          client.releaseVariableHandle(varHandle)
        }
      decoded <- attemptToTask(codec.decode(BitVector(data)))
    } yield decoded.value
  }

  override def write[T](varName: String, value: T, codec: Codec[T]): Task[Unit] = {
    for {
      varHandle <- client.getVariableHandle(varName)
      encoded <- attemptToTask(codec.encode(value))
      _ <- client
        .writeToVariable(varHandle, encoded.toByteVector)
        .doOnFinish { _ =>
          client.releaseVariableHandle(varHandle)
        }
    } yield ()
  }

  /**
    * Creates an observable that emits whenever an ADS notification is received
    *
    * A symbol handle and device notification are created and cleaned up when the observable terminates.
    *
    * @param varName PLC variable name
    * @param codec Codec between scala value and PLC value
    * @tparam T Type of the value
    * @return
    */
  override def notificationsFor[T](varName: String, codec: Codec[T]): Observable[AdsNotification[T]] = {
    // Ensures that created variable and notification handles are cleaned up
    def usingNotificationHandle[U](f: NotificationHandle => Observable[U]): Observable[U] =
      Observable.fromTask {
        for {
          varHandle <- client.getVariableHandle(varName)
          readLength = codec.sizeBound.upperBound.getOrElse(codec.sizeBound.lowerBound)
          notificationHandle <- client.getNotificationHandle(varHandle, readLength, 0, 0) // TODO cycletime
        } yield
          f(notificationHandle)
            .doOnTerminateTask(_ => client.deleteNotificationHandle(notificationHandle))
            .doOnTerminateTask(_ => client.releaseVariableHandle(varHandle))
      }.flatten

    usingNotificationHandle { handle =>
      client.notificationSamples
        .filter(_.handle == handle.value)
        .flatMap { sample =>
          Observable.fromTask {
            for {
              decodeResult <- attemptToTask(codec.decode(BitVector(sample.data)))
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
    * @param codec Codec between scala value and PLC value
    * @tparam T Type of the value
    * @return
    */
  override def consumerFor[T](varName: String, codec: Codec[T]): Consumer[T, Unit] =
    consumerBracket[T, VariableHandle](client.getVariableHandle(varName), client.releaseVariableHandle) {
      Consumer.foreachTask {
        case (handle, value) =>
          for {
            encoded <- attemptToTask(codec.encode(value))
            _ <- client.writeToVariable(handle, encoded.toByteVector)
          } yield ()
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

  override def close(): Task[Unit] = client.close()
}
