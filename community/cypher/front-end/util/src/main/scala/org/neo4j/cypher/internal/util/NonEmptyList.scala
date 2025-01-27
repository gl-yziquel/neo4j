/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.util

import org.neo4j.cypher.internal.util.NonEmptyList.IteratorConverter
import org.neo4j.cypher.internal.util.NonEmptyList.newBuilder

import scala.annotation.tailrec
import scala.collection.mutable

object NonEmptyList {

  def unapplySeq[T](input: NonEmptyList[T]): Option[Seq[T]] = Some(input.toIndexedSeq)

  def from[T](input: Iterable[T]): NonEmptyList[T] =
    from(input.iterator)

  def from[T](input: Iterator[T]): NonEmptyList[T] =
    input.asNonEmptyListOption.getOrElse(
      throw new IllegalArgumentException("Attempt to construct empty non-empty list ")
    )

  def apply[T](first: T, tail: T*): NonEmptyList[T] =
    loop(Last(first), tail.iterator).reverse

  def newBuilder[T]: mutable.Builder[T, Option[NonEmptyList[T]]] =
    new mutable.Builder[T, Option[NonEmptyList[T]]] {
      private val vecBuilder = Vector.newBuilder[T]

      override def addOne(elem: T): this.type = {
        vecBuilder += elem
        this
      }

      override def result(): Option[NonEmptyList[T]] = {
        vecBuilder.result().toNonEmptyListOption
      }

      override def clear(): Unit = {
        vecBuilder.clear()
      }
    }

  implicit class IterableConverter[T](iterable: Iterable[T]) {

    def toReverseNonEmptyListOption: Option[NonEmptyList[T]] =
      iterable.iterator.asReverseNonEmptyListOption

    def toNonEmptyListOption: Option[NonEmptyList[T]] =
      iterable.iterator.asNonEmptyListOption

    def toNonEmptyList: NonEmptyList[T] =
      toNonEmptyListOption.getOrElse(
        throw new IllegalArgumentException("Attempt to construct empty non-empty list ")
      )
  }

  implicit class VectorConverter[T](vector: Vector[T]) {

    def toReverseNonEmptyListOption: Option[NonEmptyList[T]] =
      vector.iterator.asReverseNonEmptyListOption

    def toNonEmptyListOption: Option[NonEmptyList[T]] =
      vector.reverseIterator.asReverseNonEmptyListOption
  }

  implicit class IteratorConverter[T](iterator: Iterator[T]) {

    def asReverseNonEmptyListOption: Option[NonEmptyList[T]] =
      if (iterator.isEmpty) None else Some(loop(Last(iterator.next()), iterator))

    def asNonEmptyListOption: Option[NonEmptyList[T]] =
      asReverseNonEmptyListOption.map(_.reverse)
  }

  @tailrec
  private def loop[X](acc: NonEmptyList[X], iterator: Iterator[X]): NonEmptyList[X] =
    if (iterator.hasNext) loop(Fby(iterator.next(), acc), iterator) else acc
}

// NonEmptyLists are linked lists of at least a single or multiple elements
//
// The interface follows scala collection but is not identical with it, most
// notably filter and partition have different signatures.
//
// NonEmptyLists also do not implement Traversable or Iterable directly but
// must be converted using to{Seq|Set|List|Iterable} explicitly due to
// the differing signatures.
//
sealed trait NonEmptyList[+T] {

  self =>

  def head: T

  def last: T

  def tailOption: Option[NonEmptyList[T]]

  def hasTail: Boolean
  def isLast: Boolean

  def +:[X >: T](elem: X): NonEmptyList[X] =
    Fby(elem, self)

  final def :+[X >: T](elem: X): NonEmptyList[X] =
    (elem +: self.reverse).reverse

  final def ++:[X >: T](iterable: Iterable[X]): NonEmptyList[X] =
    self.++:(iterable.iterator)

  final def ++:[X >: T](iterator: Iterator[X]): NonEmptyList[X] =
    iterator.asNonEmptyListOption match {
      case Some(prefix) => prefix.reverse.mapAndPrependReversedTo[X, X](identity, self)
      case None         => self
    }

  final def :++[X >: T](iterable: Iterable[X]): NonEmptyList[X] =
    self.:++(iterable.iterator)

  final def :++[X >: T](iterator: Iterator[X]): NonEmptyList[X] = iterator.asNonEmptyListOption match {
    case Some(suffix) => appendLoop(suffix)
    case None         => self
  }

  private def appendLoop[X >: T](suffix: NonEmptyList[X]): NonEmptyList[X] = self match {
    case Last(head)      => Fby(head, suffix)
    case Fby(head, tail) => Fby(head, tail.appendLoop(suffix))
  }

  def ++[X >: T](other: NonEmptyList[X]): NonEmptyList[X] =
    reverse.mapAndPrependReversedTo[X, X](identity, other)

  @tailrec
  final def containsAnyOf[X >: T](x: X*): Boolean = self match {
    case Last(elem)      => x.contains(elem)
    case Fby(elem, tail) => x.contains(elem) || tail.containsAnyOf(x: _*)
  }

  @tailrec
  final def foreach(f: T => Unit): Unit = self match {
    case Last(elem)      => f(elem)
    case Fby(elem, tail) => f(elem); tail.foreach(f)
  }

  final def filter[X >: T](f: X => Boolean): Option[NonEmptyList[T]] =
    foldLeft[Option[NonEmptyList[T]]](None) {
      case (None, elem)            => if (f(elem)) Some(Last(elem)) else None
      case (acc @ Some(nel), elem) => if (f(elem)) Some(Fby(elem, nel)) else acc
    }.map(_.reverse)

  final def forall[X >: T](predicate: X => Boolean): Boolean =
    !exists(x => !predicate(x))

  @tailrec
  final def exists[X >: T](predicate: X => Boolean): Boolean = self match {
    case Last(elem)                      => predicate(elem)
    case Fby(elem, _) if predicate(elem) => true
    case Fby(_, tail)                    => tail.exists(predicate)
  }

  final def map[S](f: T => S): NonEmptyList[S] = self match {
    case Fby(elem, tail) => tail.mapAndPrependReversedTo[T, S](f, Last(f(elem))).reverse
    case Last(elem)      => Last(f(elem))
  }

  final def collect[S](pf: PartialFunction[T, S]): Option[NonEmptyList[S]] =
    foldLeft(newBuilder[S]) { (builder, elem) =>
      if (pf.isDefinedAt(elem)) builder += pf(elem) else builder
    }.result()

  @tailrec
  final def mapAndPrependReversedTo[X >: T, Y](f: X => Y, acc: NonEmptyList[Y]): NonEmptyList[Y] =
    self match {
      case Fby(elem, tail) => tail.mapAndPrependReversedTo(f, Fby(f(elem), acc))
      case Last(elem)      => Fby(f(elem), acc)
    }

  final def flatMap[S](f: T => NonEmptyList[S]): NonEmptyList[S] = self match {
    case Last(elem) => f(elem)
    case _          => reverseFlatMap(f).reverse
  }

  final def reverseFlatMap[S](f: T => NonEmptyList[S]): NonEmptyList[S] = self match {
    case Fby(elem, tail) => tail.reverseFlatMapLoop(f(elem).reverse, f)
    case Last(elem)      => f(elem).reverse
  }

  final def foldLeft[A](acc0: A)(f: (A, T) => A): A =
    foldLeftLoop(acc0, f)

  final def reduceLeft[X >: T](f: (X, X) => X): X = self match {
    case Fby(head, tail) => tail.reduceLeftLoop(head, f)
    case Last(value)     => value
  }

  // Partition each element into one of two lists using f
  //
  // It holds that one of the two partitions must not be empty.
  // This is encoded in the result type, i.e. this function
  // returns
  //
  // - either a non empty list of As, and an option of a non empty list of Bs
  // - or an option of a non empty list of As, and a non empty list of Bs
  //
  final def partition[A, B](f: T => Either[A, B]): Either[
    (NonEmptyList[A], Option[NonEmptyList[B]]),
    (Option[NonEmptyList[A]], NonEmptyList[B])
  ] =
    self match {
      case Fby(elem, tail) => tail.partitionLoop(f, asPartitions(f(elem)))
      case Last(elem)      => asPartitions(f(elem))
    }

  final def groupBy[X >: T, K](f: X => K): Map[K, NonEmptyList[X]] =
    foldLeft(Map.empty[K, NonEmptyList[X]]) { (m, value) =>
      val key = f(value)
      val nel = m.get(key).map(cur => Fby(value, cur)).getOrElse(Last(value))
      m.updated(key, nel)
    }.view.mapValues(_.reverse).toMap

  final def reverse: NonEmptyList[T] = self match {
    case Fby(elem, tail) => tail.mapAndPrependReversedTo[T, T](identity, Last(elem))
    case _               => self
  }

  final def min[X >: T](implicit ordering: Ordering[X]): X =
    reduceLeft { (left, right) =>
      if (ordering.compare(left, right) <= 0) left else right
    }

  final def max[X >: T](implicit ordering: Ordering[X]): X =
    min(ordering.reverse)

  def toIterable: Iterable[T] = new Iterable[T] {

    def iterator = new Iterator[T] {
      private var remaining: Option[NonEmptyList[T]] = Some(self)

      override def hasNext: Boolean = remaining.nonEmpty

      override def next(): T = remaining match {
        case Some(nel) =>
          remaining = nel.tailOption
          nel.head
        case None =>
          throw new NoSuchElementException("next on empty iterator")
      }
    }
  }

  def size: Int

  final def toSet[X >: T]: Set[X] = foldLeft(Set.empty[X])(_ + _)
  final def toIndexedSeq: Seq[T] = foldLeft(IndexedSeq.empty[T])(_ :+ _)

  @tailrec
  private def reverseFlatMapLoop[S](
    acc: NonEmptyList[S],
    f: T => NonEmptyList[S]
  ): NonEmptyList[S] = self match {
    case Fby(elem, tail) =>
      tail.reverseFlatMapLoop(f(elem).mapAndPrependReversedTo[S, S](identity, acc), f)
    case Last(elem) => f(elem).mapAndPrependReversedTo[S, S](identity, acc)
  }

  @tailrec
  private def foldLeftLoop[A, X >: T](acc0: A, f: (A, X) => A): A = self match {
    case Last(head)      => f(acc0, head)
    case Fby(head, tail) => tail.foldLeftLoop(f(acc0, head), f)
  }

  @tailrec
  private def reduceLeftLoop[X >: T](acc: X, f: (X, X) => X): X = self match {
    case Fby(elem, tail) => tail.reduceLeftLoop(f(acc, elem), f)
    case Last(elem)      => f(acc, elem)
  }

  private def asPartitions[A, B](item: Either[A, B]): Either[
    (NonEmptyList[A], Option[NonEmptyList[B]]),
    (Option[NonEmptyList[A]], NonEmptyList[B])
  ] =
    item match {
      case Left(l)  => Left((NonEmptyList(l), None))
      case Right(r) => Right((None, NonEmptyList(r)))
    }

  @tailrec
  private def partitionLoop[A, B](
    f: T => Either[A, B],
    acc: Either[
      (NonEmptyList[A], Option[NonEmptyList[B]]),
      (Option[NonEmptyList[A]], NonEmptyList[B])
    ]
  ): Either[
    (NonEmptyList[A], Option[NonEmptyList[B]]),
    (Option[NonEmptyList[A]], NonEmptyList[B])
  ] =
    self match {
      case Fby(elem, tail) => tail.partitionLoop(f, appendToPartitions(f(elem), acc))
      case Last(elem)      => reversePartitions(appendToPartitions(f(elem), acc))
    }

  private def appendToPartitions[A, B](
    value: Either[A, B],
    acc: Either[
      (NonEmptyList[A], Option[NonEmptyList[B]]),
      (Option[NonEmptyList[A]], NonEmptyList[B])
    ]
  ): Either[
    (NonEmptyList[A], Option[NonEmptyList[B]]),
    (Option[NonEmptyList[A]], NonEmptyList[B])
  ] =
    (value, acc) match {
      case (Left(elem), Left((lefts, optRights))) => Left((Fby(elem, lefts), optRights))
      case (Left(elem), Right((optLefts, rights))) =>
        Right((prependToOptionalNonEmptyList(elem, optLefts), rights))
      case (Right(elem), Left((lefts, optRights))) =>
        Left((lefts, prependToOptionalNonEmptyList(elem, optRights)))
      case (Right(elem), Right((optLefts, rights))) => Right((optLefts, Fby(elem, rights)))
    }

  private def reversePartitions[A, B](
    acc: Either[
      (NonEmptyList[A], Option[NonEmptyList[B]]),
      (Option[NonEmptyList[A]], NonEmptyList[B])
    ]
  ): Either[
    (NonEmptyList[A], Option[NonEmptyList[B]]),
    (Option[NonEmptyList[A]], NonEmptyList[B])
  ] =
    acc match {
      case Left((lefts, optRights))  => Left((lefts.reverse, optRights.map(_.reverse)))
      case Right((optLefts, rights)) => Right((optLefts.map(_.reverse), rights.reverse))
    }

  private def prependToOptionalNonEmptyList[X](
    elem: X,
    optNel: Option[NonEmptyList[X]]
  ): Option[NonEmptyList[X]] =
    optNel.map { nel =>
      Fby(elem, nel)
    } orElse Some(Last(elem))
}

final case class Fby[+T](head: T, tail: NonEmptyList[T]) extends NonEmptyList[T] {
  override def last: T = tail.last
  override def tailOption: Option[NonEmptyList[T]] = Some(tail)
  override def hasTail: Boolean = true
  override def isLast: Boolean = false
  override def toString = s"${head.toString}, ${tail.toString}"
  override def size = 1 + tail.size
}

final case class Last[+T](head: T) extends NonEmptyList[T] {
  override def last: T = head
  override def tailOption: Option[NonEmptyList[T]] = None
  override def hasTail: Boolean = false
  override def isLast: Boolean = true
  override def toString = s"${head.toString}"
  override def size = 1
}
