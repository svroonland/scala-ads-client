package nl.vroste.adsclient.internal.util

import monix.eval.Task
import monix.reactive.Observable

object ObservableUtil {

  /**
   * Creates an observable that depends on an asynchronously generated resource
   *
    * Analog of [[monix.eval.Task.bracket]] for observables
   *
    * Executes `acquire` upon subscription and feeds its result to `use`. Emits the elements emitted by the observable
   * returned by `use`. When the observable is completed, `release` is executed
   * (which takes the result of acquire as parameter)
   */
  def bracket[T, R](acquire: Task[R])(use: R => Observable[T])(release: R => Task[Unit]): Observable[T] = {
    val task = acquire.map { resource =>
      use(resource).doOnTerminateTask(_ =>
        release(resource).forkAndForget
      ) // This has to be forked so it can run when the subscription ends
    }
    Observable.fromTask(task).flatten
  }
}
