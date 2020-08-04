package org.scalacheck.effect

import scala.collection.immutable.Stream
import scala.collection.immutable.Stream.#::

import cats.MonadError
import cats.implicits._
import org.scalacheck.{Arbitrary, Gen, Prop, Shrink, Test}
import org.scalacheck.rng.Seed
import org.scalacheck.util.{FreqMap, Pretty}
import scala.collection.immutable.Stream.Cons

sealed trait PropF[F[_]] {
  implicit val F: MonadError[F, Throwable]

  private[effect] def addArg(arg: Prop.Arg[Any]): PropF[F] =
    mapResult(r => r.copy(args = arg :: r.args))

  private[effect] def provedToTrue: PropF[F] =
    mapResult { r =>
      if (r.status == Prop.Proof) r.copy(status = Prop.True)
      else r
    }

  private def mapResult(f: PropF.Result[F] => PropF.Result[F]): PropF[F] =
    this match {
      case r: PropF.Result[F] => f(r)
      case PropF.Cont(g)      => PropF.Cont(p => g(p).mapResult(f))
      case PropF.Eval(effect) => PropF.Eval(effect.map(_.mapResult(f)))
    }

  private def checkOne(params: Gen.Parameters): F[PropF.Result[F]] = {
    this match {
      case r: PropF.Result[F] =>
        F.pure(r)
      case PropF.Eval(fa) =>
        fa.flatMap { next =>
          next.checkOne(Prop.slideSeed(params))
        }
      case PropF.Cont(next) =>
        next(params).checkOne(Prop.slideSeed(params))
    }
  }

  def check(
      testParams: Test.Parameters = Test.Parameters.default,
      genParams: Gen.Parameters = Gen.Parameters.default
  ): F[Test.Result] = {

    import testParams.{minSuccessfulTests, minSize, maxDiscardRatio, maxSize}
    val sizeStep = (maxSize - minSize) / minSuccessfulTests
    val maxDiscarded = minSuccessfulTests * maxDiscardRatio

    def loop(params: Gen.Parameters, passed: Int, discarded: Int): F[Test.Result] = {
      if (passed >= minSuccessfulTests)
        F.pure(Test.Result(Test.Passed, passed, discarded, FreqMap.empty))
      else if (discarded >= maxDiscarded)
        F.pure(Test.Result(Test.Exhausted, passed, discarded, FreqMap.empty))
      else {
        val size = minSize.toDouble + sizeStep
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
}

object PropF {
  def apply[F[_]](
      f: Gen.Parameters => PropF[F]
  )(implicit F: MonadError[F, Throwable]): PropF[F] = Cont(f)

  private[effect] case class Cont[F[_]](f: Gen.Parameters => PropF[F])(implicit
      val F: MonadError[F, Throwable]
  ) extends PropF[F]

  private[effect] def undecided[F[_]](implicit F: MonadError[F, Throwable]): PropF[F] =
    Result(Prop.Undecided, Nil, Set.empty, Set.empty)

  private[effect] case class Result[F[_]](
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

  private[effect] case class Eval[F[_], A](effect: F[PropF[F]])(implicit
      val F: MonadError[F, Throwable]
  ) extends PropF[F]

  implicit def effectOfPropFToPropF[F[_]](
      fa: F[PropF[F]]
  )(implicit F: MonadError[F, Throwable]): PropF[F] =
    Eval[F, Result[F]](
      fa.handleError(t => Result[F](Prop.Exception(t), Nil, Set.empty, Set.empty))
    )

  implicit def effectOfUnitToPropF[F[_]](
      fu: F[Unit]
  )(implicit F: MonadError[F, Throwable]): PropF[F] =
    Eval[F, Result[F]](
      fu.as(Result[F](Prop.True, Nil, Set.empty, Set.empty): PropF[F])
        .handleError(t => Result[F](Prop.Exception(t), Nil, Set.empty, Set.empty))
    )

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
        case e: Gen.RetrievalError => PropF.undecided
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
            case Right((x2, r2)) => F.pure(res)
            case Left((x2, r2))  => shrinker(x2, replOrig(r, r2), shrinks + 1, orig)
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
          Eval(result(x).flatMap { r =>
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
