package com.vroste.adsclient.internal.util

import monix.eval.Task
import monix.reactive.Consumer

object ConsumerUtil {
  /**
    * Creates a [[Consumer]] that creates a resource before handling the first element and cleans it up after
    * handling the last element.
    *
    * Takes as argument a consumer that takes a tuple of the resource and a value
    *
    * The resource is created by a task that is memoized
    *
    * @param acquire Create the resource asychronously. The result is memoized
    * @param release Cleanup the resource after the last
    * @param inner   Consumer for tuples of the resource and a value
    * @tparam T Type of elements to consume
    * @tparam R Type of the resource
    * @return A consumer of elements of type T, when executed will result in a value of type Unit
    */
  def bracket[T, R](acquire: Task[R], release: R => Task[Unit])(inner: Consumer[(R, T), Unit]): Consumer[T, Unit] = {
    val resourceT = acquire.memoize

    inner
      .transformInput[T](_.mapTask(t => resourceT.map((_, t))))
      .mapTask(_ => resourceT.flatMap(release))
  }
}
