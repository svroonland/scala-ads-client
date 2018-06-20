package com.vroste.adsclient

import monix.eval.{MVar, Task}

class CountingSemaphore {
  private val locked: MVar[Unit] = MVar(()) // A full MVar means free, empty MVar means locked
  private val count: MVar[Int] = MVar(0)
  private val zero: MVar[Unit] = MVar(())

  /**
    * Increment the count atomically
    *
    * @return
    */
  def increment: Task[Unit] = withLock {
    for {
      i <- count.take
      _ <- if (i == 0) zero.take else Task.unit
      _ <- count.put(i + 1)
    } yield ()
  }

  /**
    * Decrement the count atomically
    *
    * @return
    */
  def decrement: Task[Unit] = withLock {
    for {
      i <- count.take
      _ <- if (i == 1) zero.put(()) else Task.unit
      _ <- count.put(i - 1)
    } yield ()
  }

  /**
    * Task that completes when the count is zero
    */
  def awaitZero: Task[Unit] = withLock { zero.read }

  private def withLock[T](f: => Task[T]): Task[T] = for {
    _ <- locked.take
    r <- f.doOnCancel(locked.put(()))
    _ <- locked.put(())
  } yield r
}
