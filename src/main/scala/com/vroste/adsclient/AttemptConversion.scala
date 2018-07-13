package com.vroste.adsclient

import monix.eval.Task
import monix.reactive.Observable
import scodec.Attempt

object AttemptConversion {
  implicit class AttemptToTask[T](val attempt: Attempt[T]) extends AnyVal {
    def toTask: Task[T] =
      attempt.fold(cause => Task.raiseError(AdsClientException(cause.messageWithContext)), Task.pure)
  }

  implicit class AttemptToObservable[T](val attempt: Attempt[T]) extends AnyVal {
    def toObservable: Observable[T] = Observable.fromTask(attempt.toTask)
  }
}
