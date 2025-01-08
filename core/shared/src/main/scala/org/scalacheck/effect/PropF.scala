/*
 * Copyright 2021 Typelevel
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

package org.scalacheck.effect

import scala.collection.immutable.Stream
import cats.MonadError
import cats.syntax.all.*
import org.scalacheck.{Arbitrary, Gen, Prop, Shrink, Test}
import org.scalacheck.util.{FreqMap, Pretty}
import org.typelevel.scalaccompat.annotation.*

/** An effectful property.
  *
  * Effectful properties are ones in which each sample evaluates in a type constructor. That is,
  * instead of directly computing a result from a single sample of generated values, the result is
  * computed in some effect `F[_]` -- e.g., `cats.effect.IO` or `scala.concurrent.Future`. A
  * property which computes in an effect `F[_]` has the type `PropF[F]`.
  *
  * `PropF[F]` instances can be constructed for any effect which has a `MonadError[F, Throwable]`
  * instance.
  *
  * The most common way to construct `PropF[F]` values is by using one of the `forAllF` methods on
  * the `PropF` companion. These are analogous to `Prop.forAll` from ScalaCheck. When computing the
  * result of a single sample, `F[Unit]` values are treated as successes and any exceptions thrown
  * are treated as falsifications.
  */
sealed trait PropF[F[_]] {
  implicit val F: MonadError[F, Throwable]

  def map(f: PropF.Result[F] => PropF.Result[F]): PropF[F] =
    this match {
      case r: PropF.Result[F]     => f(r)
      case PropF.Parameterized(g) => PropF.Parameterized(p => g(p).map(f))
      case PropF.Suspend(effect)  => PropF.Suspend(effect.map(_.map(f)))
    }

  def flatMap(f: PropF.Result[F] => PropF[F]): PropF[F] =
    this match {
      case r: PropF.Result[F]     => f(r)
      case PropF.Parameterized(g) => PropF.Parameterized(p => g(p).flatMap(f))
      case PropF.Suspend(effect)  => PropF.Suspend(effect.map(_.flatMap(f)))
    }

  /** Checks a single sample. */
  def checkOne(params: Gen.Parameters = Gen.Parameters.default): F[PropF.Result[F]] = {
    this match {
      case r: PropF.Result[F] =>
        F.pure(r)
      case PropF.Suspend(fa) =>
        fa.flatMap { next =>
          next.checkOne(Prop.slideSeed(params))
        }
      case PropF.Parameterized(next) =>
        next(Prop.slideSeed(params)).checkOne(Prop.slideSeed(params))
    }
  }

  /** Checks this property. */
  def check(
      testParams: Test.Parameters = Test.Parameters.default,
      genParams: Gen.Parameters = Gen.Parameters.default
  ): F[Test.Result] = {

    import testParams.{minSuccessfulTests, minSize, maxDiscardRatio, maxSize}
    val sizeStep = (maxSize - minSize) / minSuccessfulTests.toDouble
    val maxDiscarded = minSuccessfulTests * maxDiscardRatio

    def loop(params: Gen.Parameters, passed: Int, discarded: Int): F[Test.Result] = {
      if (passed >= minSuccessfulTests)
        F.pure(Test.Result(Test.Passed, passed, discarded, FreqMap.empty))
      else if (discarded >= maxDiscarded)
        F.pure(Test.Result(Test.Exhausted, passed, discarded, FreqMap.empty))
      else {
        val count = passed + discarded
        val size = minSize.toDouble + (sizeStep * count)
        checkOne(params.withSize(size.round.toInt)).flatMap { result =>
          result.status match {
            case Prop.True =>
              loop(Prop.slideSeed(params), passed + 1, discarded)
            case Prop.Proof =>
              F.pure(Test.Result(Test.Proved(result.args), passed + 1, discarded, FreqMap.empty))
            case Prop.Exception(e) =>
              F.pure(
                Test.Result(
                  Test.PropException(result.args, e, result.labels),
                  passed,
                  discarded,
                  FreqMap.empty
                )
              )
            case Prop.False =>
              F.pure(
                Test.Result(
                  Test.Failed(result.args, result.labels),
                  passed,
                  discarded,
                  FreqMap.empty
                )
              )
            case Prop.Undecided =>
              loop(Prop.slideSeed(params), passed, discarded + 1)
          }
        }
      }
    }

    loop(genParams, 0, 0)
  }

  private[effect] def addArg(arg: Prop.Arg[Any]): PropF[F] =
    map(r => r.copy(args = arg :: r.args))

  private[effect] def provedToTrue: PropF[F] =
    map { r =>
      if (r.status == Prop.Proof) r.copy(status = Prop.True)
      else r
    }
}

object PropF {
  def apply[F[_]](
      f: Gen.Parameters => PropF[F]
  )(implicit F: MonadError[F, Throwable]): PropF[F] = Parameterized(f)

  private[effect] case class Parameterized[F[_]](f: Gen.Parameters => PropF[F])(implicit
      val F: MonadError[F, Throwable]
  ) extends PropF[F]

  case class Result[F[_]](
      status: Prop.Status,
      args: List[Prop.Arg[Any]],
      collected: Set[Any],
      labels: Set[String]
  )(implicit val F: MonadError[F, Throwable])
      extends PropF[F] {
    def failure: Boolean =
      status match {
        case Prop.False        => true
        case Prop.Exception(_) => true
        case _                 => false
      }
  }

  def status[F[_]](status: Prop.Status)(implicit F: MonadError[F, Throwable]): PropF[F] =
    Result(status, Nil, Set.empty, Set.empty)

  def undecided[F[_]](implicit F: MonadError[F, Throwable]): PropF[F] =
    status(Prop.Undecided)

  def falsified[F[_]](implicit F: MonadError[F, Throwable]): PropF[F] =
    status(Prop.False)

  def proved[F[_]](implicit F: MonadError[F, Throwable]): PropF[F] =
    status(Prop.Proof)

  def passed[F[_]](implicit F: MonadError[F, Throwable]): PropF[F] =
    status(Prop.True)

  def exception[F[_]](t: Throwable)(implicit F: MonadError[F, Throwable]): PropF[F] =
    status(Prop.Exception(t))

  def boolean[F[_]](
      b: Boolean
  )(implicit F: MonadError[F, Throwable]): PropF[F] = if (b) passed else falsified

  private[effect] case class Suspend[F[_], A](effect: F[PropF[F]])(implicit
      val F: MonadError[F, Throwable]
  ) extends PropF[F]

  implicit def effectOfPropFToPropF[F[_]](
      fa: F[PropF[F]]
  )(implicit F: MonadError[F, Throwable]): PropF[F] =
    Suspend[F, Result[F]](
      fa.handleError(t => Result[F](Prop.Exception(t), Nil, Set.empty, Set.empty))
    )

  implicit def effectOfUnitToPropF[F[_]](
      fu: F[Unit]
  )(implicit F: MonadError[F, Throwable]): PropF[F] =
    Suspend[F, Result[F]](
      fu.as(Result[F](Prop.True, Nil, Set.empty, Set.empty): PropF[F])
        .handleError(t => Result[F](Prop.Exception(t), Nil, Set.empty, Set.empty))
    )

  implicit def effectOfBooleanToPropF[F[_]](
      fb: F[Boolean]
  )(implicit F: MonadError[F, Throwable]): PropF[F] =
    effectOfPropFToPropF(F.map(fb)(boolean(_)))

  def forAllNoShrinkF[F[_], T1, P](
      g1: Gen[T1]
  )(
      f: T1 => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty
  ): PropF[F] =
    PropF[F] { params0 =>
      val (params, seed) = Prop.startSeed(params0)
      try {
        val r = g1.doPureApply(params, seed)
        r.retrieve match {
          case Some(x) =>
            val labels = r.labels.mkString(",")
            toProp(f(x)).addArg(Prop.Arg(labels, x, 0, x, pp1(x), pp1(x))).provedToTrue
          case None => PropF.undecided
        }
      } catch {
        case _: Gen.RetrievalError => PropF.undecided
      }
    }

  def forAllNoShrinkF[F[_], T1, P](
      f: T1 => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      pp1: T1 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(a1.arbitrary)(f)

  def forAllNoShrinkF[F[_], T1, T2, P](
      g1: Gen[T1],
      g2: Gen[T2]
  )(
      f: (T1, T2) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty,
      pp2: T2 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(g1)(t1 => forAllNoShrinkF(g2)(f(t1, _)))

  def forAllNoShrinkF[F[_], T1, T2, P](
      f: (T1, T2) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      pp2: T2 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(a1.arbitrary, a2.arbitrary)(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3]
  )(
      f: (T1, T2, T3) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty,
      pp2: T2 => Pretty,
      pp3: T3 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(g1)(t1 => forAllNoShrinkF(g2, g3)(f(t1, _, _)))

  def forAllNoShrinkF[F[_], T1, T2, T3, P](
      f: (T1, T2, T3) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      pp3: T3 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(a1.arbitrary, a2.arbitrary, a3.arbitrary)(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3],
      g4: Gen[T4]
  )(
      f: (T1, T2, T3, T4) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty,
      pp2: T2 => Pretty,
      pp3: T3 => Pretty,
      pp4: T4 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(g1)(t1 => forAllNoShrinkF(g2, g3, g4)(f(t1, _, _, _)))

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, P](
      f: (T1, T2, T3, T4) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      pp3: T3 => Pretty,
      a4: Arbitrary[T4],
      pp4: T4 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(a1.arbitrary, a2.arbitrary, a3.arbitrary, a4.arbitrary)(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3],
      g4: Gen[T4],
      g5: Gen[T5]
  )(
      f: (T1, T2, T3, T4, T5) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty,
      pp2: T2 => Pretty,
      pp3: T3 => Pretty,
      pp4: T4 => Pretty,
      pp5: T5 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(g1)(t1 => forAllNoShrinkF(g2, g3, g4, g5)(f(t1, _, _, _, _)))

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, P](
      f: (T1, T2, T3, T4, T5) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      pp3: T3 => Pretty,
      a4: Arbitrary[T4],
      pp4: T4 => Pretty,
      a5: Arbitrary[T5],
      pp5: T5 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(
      a1.arbitrary,
      a2.arbitrary,
      a3.arbitrary,
      a4.arbitrary,
      a5.arbitrary
    )(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3],
      g4: Gen[T4],
      g5: Gen[T5],
      g6: Gen[T6]
  )(
      f: (T1, T2, T3, T4, T5, T6) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty,
      pp2: T2 => Pretty,
      pp3: T3 => Pretty,
      pp4: T4 => Pretty,
      pp5: T5 => Pretty,
      pp6: T6 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(g1)(t1 => forAllNoShrinkF(g2, g3, g4, g5, g6)(f(t1, _, _, _, _, _)))

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, P](
      f: (T1, T2, T3, T4, T5, T6) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      pp3: T3 => Pretty,
      a4: Arbitrary[T4],
      pp4: T4 => Pretty,
      a5: Arbitrary[T5],
      pp5: T5 => Pretty,
      a6: Arbitrary[T6],
      pp6: T6 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(
      a1.arbitrary,
      a2.arbitrary,
      a3.arbitrary,
      a4.arbitrary,
      a5.arbitrary,
      a6.arbitrary
    )(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, T7, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3],
      g4: Gen[T4],
      g5: Gen[T5],
      g6: Gen[T6],
      g7: Gen[T7]
  )(
      f: (T1, T2, T3, T4, T5, T6, T7) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty,
      pp2: T2 => Pretty,
      pp3: T3 => Pretty,
      pp4: T4 => Pretty,
      pp5: T5 => Pretty,
      pp6: T6 => Pretty,
      pp7: T7 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(g1)(t1 => forAllNoShrinkF(g2, g3, g4, g5, g6, g7)(f(t1, _, _, _, _, _, _)))

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, T7, P](
      f: (T1, T2, T3, T4, T5, T6, T7) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      pp3: T3 => Pretty,
      a4: Arbitrary[T4],
      pp4: T4 => Pretty,
      a5: Arbitrary[T5],
      pp5: T5 => Pretty,
      a6: Arbitrary[T6],
      pp6: T6 => Pretty,
      a7: Arbitrary[T7],
      pp7: T7 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(
      a1.arbitrary,
      a2.arbitrary,
      a3.arbitrary,
      a4.arbitrary,
      a5.arbitrary,
      a6.arbitrary,
      a7.arbitrary
    )(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, T7, T8, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3],
      g4: Gen[T4],
      g5: Gen[T5],
      g6: Gen[T6],
      g7: Gen[T7],
      g8: Gen[T8]
  )(
      f: (T1, T2, T3, T4, T5, T6, T7, T8) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty,
      pp2: T2 => Pretty,
      pp3: T3 => Pretty,
      pp4: T4 => Pretty,
      pp5: T5 => Pretty,
      pp6: T6 => Pretty,
      pp7: T7 => Pretty,
      pp8: T8 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(g1)(t1 =>
      forAllNoShrinkF(g2, g3, g4, g5, g6, g7, g8)(f(t1, _, _, _, _, _, _, _))
    )

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, T7, T8, P](
      f: (T1, T2, T3, T4, T5, T6, T7, T8) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      pp3: T3 => Pretty,
      a4: Arbitrary[T4],
      pp4: T4 => Pretty,
      a5: Arbitrary[T5],
      pp5: T5 => Pretty,
      a6: Arbitrary[T6],
      pp6: T6 => Pretty,
      a7: Arbitrary[T7],
      pp7: T7 => Pretty,
      a8: Arbitrary[T8],
      pp8: T8 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(
      a1.arbitrary,
      a2.arbitrary,
      a3.arbitrary,
      a4.arbitrary,
      a5.arbitrary,
      a6.arbitrary,
      a7.arbitrary,
      a8.arbitrary
    )(f)

  @nowarn213("""msg=(?:class|object) Stream in package (?:scala\.collection\.)?immutable is deprecated \(since 2.13.0\): Use LazyList \(which is fully lazy\) instead of Stream \(which has a lazy tail only\)""")
  @nowarn3("""msg=(?:class|object) Stream in package (?:scala\.collection\.)?immutable is deprecated since 2\.13\.0: Use LazyList \(which is fully lazy\) instead of Stream \(which has a lazy tail only\)""")
  def forAllShrinkF[F[_], T, P](
      gen: Gen[T],
      shrink: T => Stream[T]
  )(
      f: T => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp: T => Pretty
  ): PropF[F] =
    PropF[F] { prms0 =>
      import scala.collection.immutable.Stream.#::

      val (prms, seed) = Prop.startSeed(prms0)
      val gr = gen.doApply(prms, seed)
      val labels = gr.labels.mkString(",")

      def result(x: T): F[Result[F]] =
        toProp(f(x)).provedToTrue.checkOne(Prop.slideSeed(prms0))

      def getFirstFailure(xs: Stream[T]): F[Either[(T, Result[F]), (T, Result[F])]] = {
        assert(!xs.isEmpty, "Stream cannot be empty")
        val results: Stream[(T, F[Result[F]])] = xs.map(x => (x, result(x)))
        val (firstT, firstResF) = results.head
        firstResF.flatMap { firstRes =>
          def loop(
              results: Stream[(T, F[Result[F]])]
          ): F[Either[(T, Result[F]), (T, Result[F])]] = {
            results match {
              case hd #:: tl =>
                hd._2.flatMap { r =>
                  if (r.failure) F.pure(Left((hd._1, r)))
                  else loop(tl)
                }
              case _ => F.pure(Right((firstT, firstRes)))
            }
          }
          loop(results)
        }
      }

      def shrinker(x: T, r: Result[F], shrinks: Int, orig: T): F[PropF[F]] = {
        val xs = shrink(x).filter(gr.sieve)
        val res = r.addArg(Prop.Arg(labels, x, shrinks, orig, pp(x), pp(orig)))
        if (xs.isEmpty) F.pure(res)
        else
          getFirstFailure(xs).flatMap {
            case Right((_, _))  => F.pure(res)
            case Left((x2, r2)) => shrinker(x2, replOrig(r, r2), shrinks + 1, orig)
          }
      }

      def replOrig(r0: Result[F], r1: Result[F]): Result[F] =
        (r0.args, r1.args) match {
          case (a0 :: _, a1 :: as) =>
            r1.copy(
              args = a1.copy(
                origArg = a0.origArg,
                prettyOrigArg = a0.prettyOrigArg
              ) :: as
            )
          case _ => r1
        }

      gr.retrieve match {
        case None => PropF.undecided
        case Some(x) =>
          Suspend(result(x).flatMap { r =>
            if (r.failure && prms.useLegacyShrinking) shrinker(x, r, 0, x)
            else F.pure(r.addArg(Prop.Arg(labels, x, 0, x, pp(x), pp(x))))
          })
      }
    }

  def forAllF[F[_], T1, P](
      g1: Gen[T1]
  )(
      f: T1 => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      s1: Shrink[T1],
      pp1: T1 => Pretty
  ): PropF[F] = forAllShrinkF(g1, s1.shrink)(f)

  def forAllF[F[_], T1, T2, P](
      g1: Gen[T1],
      g2: Gen[T2]
  )(
      f: (T1, T2) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      s2: Shrink[T2],
      pp2: T2 => Pretty
  ): PropF[F] = forAllF(g1)(t1 => forAllF(g2)(f(t1, _)))

  def forAllF[F[_], T1, T2, T3, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3]
  )(
      f: (T1, T2, T3) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      s3: Shrink[T3],
      pp3: T3 => Pretty
  ): PropF[F] = forAllF(g1)(t1 => forAllF(g2, g3)(f(t1, _, _)))

  def forAllF[F[_], T1, T2, T3, T4, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3],
      g4: Gen[T4]
  )(
      f: (T1, T2, T3, T4) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      s3: Shrink[T3],
      pp3: T3 => Pretty,
      s4: Shrink[T4],
      pp4: T4 => Pretty
  ): PropF[F] = forAllF(g1)(t1 => forAllF(g2, g3, g4)(f(t1, _, _, _)))

  def forAllF[F[_], T1, T2, T3, T4, T5, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3],
      g4: Gen[T4],
      g5: Gen[T5]
  )(
      f: (T1, T2, T3, T4, T5) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      s3: Shrink[T3],
      pp3: T3 => Pretty,
      s4: Shrink[T4],
      pp4: T4 => Pretty,
      s5: Shrink[T5],
      pp5: T5 => Pretty
  ): PropF[F] = forAllF(g1)(t1 => forAllF(g2, g3, g4, g5)(f(t1, _, _, _, _)))

  def forAllF[F[_], T1, T2, T3, T4, T5, T6, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3],
      g4: Gen[T4],
      g5: Gen[T5],
      g6: Gen[T6]
  )(
      f: (T1, T2, T3, T4, T5, T6) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      s3: Shrink[T3],
      pp3: T3 => Pretty,
      s4: Shrink[T4],
      pp4: T4 => Pretty,
      s5: Shrink[T5],
      pp5: T5 => Pretty,
      s6: Shrink[T6],
      pp6: T6 => Pretty
  ): PropF[F] = forAllF(g1)(t1 => forAllF(g2, g3, g4, g5, g6)(f(t1, _, _, _, _, _)))

  def forAllF[F[_], T1, T2, T3, T4, T5, T6, T7, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3],
      g4: Gen[T4],
      g5: Gen[T5],
      g6: Gen[T6],
      g7: Gen[T7]
  )(
      f: (T1, T2, T3, T4, T5, T6, T7) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      s3: Shrink[T3],
      pp3: T3 => Pretty,
      s4: Shrink[T4],
      pp4: T4 => Pretty,
      s5: Shrink[T5],
      pp5: T5 => Pretty,
      s6: Shrink[T6],
      pp6: T6 => Pretty,
      s7: Shrink[T7],
      pp7: T7 => Pretty
  ): PropF[F] = forAllF(g1)(t1 => forAllF(g2, g3, g4, g5, g6, g7)(f(t1, _, _, _, _, _, _)))

  def forAllF[F[_], T1, T2, T3, T4, T5, T6, T7, T8, P](
      g1: Gen[T1],
      g2: Gen[T2],
      g3: Gen[T3],
      g4: Gen[T4],
      g5: Gen[T5],
      g6: Gen[T6],
      g7: Gen[T7],
      g8: Gen[T8]
  )(
      f: (T1, T2, T3, T4, T5, T6, T7, T8) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      s3: Shrink[T3],
      pp3: T3 => Pretty,
      s4: Shrink[T4],
      pp4: T4 => Pretty,
      s5: Shrink[T5],
      pp5: T5 => Pretty,
      s6: Shrink[T6],
      pp6: T6 => Pretty,
      s7: Shrink[T7],
      pp7: T7 => Pretty,
      s8: Shrink[T8],
      pp8: T8 => Pretty
  ): PropF[F] = forAllF(g1)(t1 => forAllF(g2, g3, g4, g5, g6, g7, g8)(f(t1, _, _, _, _, _, _, _)))

  def forAllF[F[_], T1, P](
      f: T1 => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      s1: Shrink[T1],
      pp1: T1 => Pretty
  ): PropF[F] = forAllShrinkF(a1.arbitrary, s1.shrink)(f)

  def forAllF[F[_], T1, T2, P](
      f: (T1, T2) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      s2: Shrink[T2],
      pp2: T2 => Pretty
  ): PropF[F] = forAllF((t1: T1) => forAllF(f(t1, _)))

  def forAllF[F[_], T1, T2, T3, P](
      f: (T1, T2, T3) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      s3: Shrink[T3],
      pp3: T3 => Pretty
  ): PropF[F] = forAllF((t1: T1) => forAllF(f(t1, _, _)))

  def forAllF[F[_], T1, T2, T3, T4, P](
      f: (T1, T2, T3, T4) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      s3: Shrink[T3],
      pp3: T3 => Pretty,
      a4: Arbitrary[T4],
      s4: Shrink[T4],
      pp4: T4 => Pretty
  ): PropF[F] = forAllF((t1: T1) => forAllF(f(t1, _, _, _)))

  def forAllF[F[_], T1, T2, T3, T4, T5, P](
      f: (T1, T2, T3, T4, T5) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      s3: Shrink[T3],
      pp3: T3 => Pretty,
      a4: Arbitrary[T4],
      s4: Shrink[T4],
      pp4: T4 => Pretty,
      a5: Arbitrary[T5],
      s5: Shrink[T5],
      pp5: T5 => Pretty
  ): PropF[F] = forAllF((t1: T1) => forAllF(f(t1, _, _, _, _)))

  def forAllF[F[_], T1, T2, T3, T4, T5, T6, P](
      f: (T1, T2, T3, T4, T5, T6) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      s3: Shrink[T3],
      pp3: T3 => Pretty,
      a4: Arbitrary[T4],
      s4: Shrink[T4],
      pp4: T4 => Pretty,
      a5: Arbitrary[T5],
      s5: Shrink[T5],
      pp5: T5 => Pretty,
      a6: Arbitrary[T6],
      s6: Shrink[T6],
      pp6: T6 => Pretty
  ): PropF[F] = forAllF((t1: T1) => forAllF(f(t1, _, _, _, _, _)))

  def forAllF[F[_], T1, T2, T3, T4, T5, T6, T7, P](
      f: (T1, T2, T3, T4, T5, T6, T7) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      s3: Shrink[T3],
      pp3: T3 => Pretty,
      a4: Arbitrary[T4],
      s4: Shrink[T4],
      pp4: T4 => Pretty,
      a5: Arbitrary[T5],
      s5: Shrink[T5],
      pp5: T5 => Pretty,
      a6: Arbitrary[T6],
      s6: Shrink[T6],
      pp6: T6 => Pretty,
      a7: Arbitrary[T7],
      s7: Shrink[T7],
      pp7: T7 => Pretty
  ): PropF[F] = forAllF((t1: T1) => forAllF(f(t1, _, _, _, _, _, _)))

  def forAllF[F[_], T1, T2, T3, T4, T5, T6, T7, T8, P](
      f: (T1, T2, T3, T4, T5, T6, T7, T8) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      a1: Arbitrary[T1],
      s1: Shrink[T1],
      pp1: T1 => Pretty,
      a2: Arbitrary[T2],
      s2: Shrink[T2],
      pp2: T2 => Pretty,
      a3: Arbitrary[T3],
      s3: Shrink[T3],
      pp3: T3 => Pretty,
      a4: Arbitrary[T4],
      s4: Shrink[T4],
      pp4: T4 => Pretty,
      a5: Arbitrary[T5],
      s5: Shrink[T5],
      pp5: T5 => Pretty,
      a6: Arbitrary[T6],
      s6: Shrink[T6],
      pp6: T6 => Pretty,
      a7: Arbitrary[T7],
      s7: Shrink[T7],
      pp7: T7 => Pretty,
      a8: Arbitrary[T8],
      s8: Shrink[T8],
      pp8: T8 => Pretty
  ): PropF[F] = forAllF((t1: T1) => forAllF(f(t1, _, _, _, _, _, _, _)))
}
