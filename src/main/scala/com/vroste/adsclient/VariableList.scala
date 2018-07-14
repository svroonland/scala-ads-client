package com.vroste.adsclient

import scodec.{Codec, HListCodecEnrichedWithHListSupport}
import shapeless.ops.hlist.{Length, Prepend, Split}
import shapeless.{::, HList, HNil, Nat}

/**
  * List of PLC variables along with their codecs for use in ADS SUM commands
  *
  * Can be used for:
  * - reading many variables at once
  * - reading many variables at once using a list of handles
  * - writing to many variables at once
  * - writing to many variables at once using a list of handles
  * - creating handles for many variables at once
  * - releasing handles for many variables
  *
  * Uses shapeless HList
  */
class VariableList[T <: HList] private(
                                        private[adsclient] val variables: Seq[String],
                                        private[adsclient] val sizes: Seq[Long],
                                        private[adsclient] val codec: Codec[T]) {
  /**
    * Adds a variable of type [[U]] to the list
    *
    * The implicits and other type parameters are needed to satisfy shapeless
    *
    * @param varName Name of the variable
    * @param codecU  Codec for a value of type [[U]]
    */
  def +[U, TU <: HList, KLen <: Nat](varName: String, codecU: Codec[U])(implicit prepend: Prepend.Aux[T, U :: HNil, TU], length: Length.Aux[T, KLen], split: Split.Aux[TU, KLen, T, U :: HNil]): VariableList[TU] =
    new VariableList(variables :+ varName, sizes :+ codec.sizeBound.exact.get, codec ::: codecU.hlist)
}

object VariableList {
  def apply[T](varName: String, codec: Codec[T]): VariableList[T :: HNil] =
    new VariableList(Seq(varName), Seq(codec.sizeBound.exact.get), codec.hlist)
}
