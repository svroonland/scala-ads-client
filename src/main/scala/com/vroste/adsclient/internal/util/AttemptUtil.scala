package com.vroste.adsclient.internal.util

import com.vroste.adsclient.AdsClientException
import monix.eval.Task
import monix.reactive.Observable
import scodec.Attempt

object AttemptUtil {

  implicit class AttemptToTask[T](val attempt: Attempt[T]) extends AnyVal {
    def toTask: Task[T] =
      attempt.fold(cause => Task.raiseError(AdsClientException(cause.messageWithContext)), Task.pure)
  }

  implicit class AttemptToObservable[T](val attempt: Attempt[T]) extends AnyVal {
    def toObservable: Observable[T] = Observable.fromTask(attempt.toTask)
  }

  implicit class AttemptSequence[T](val seq: Seq[Attempt[T]]) extends AnyVal {
    /**
      * Turn a Seq[Attempt[T]] into an Attempt[Seq[T]]
      *
      * If any of the attempts have failed, the result will be a failed attempt
      * @return
      */
    def sequence: Attempt[Seq[T]] =
      seq.foldLeft(Attempt.successful(Seq.empty[T])) {
        case (as, a) => as.flatMap(s => a.map(s :+ _))
      }
  }
}
