package org.scalacheck.effect

import cats.MonadError
import cats.implicits._
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Test
import org.scalacheck.rng.Seed
import org.scalacheck.util.FreqMap
import org.scalacheck.util.Pretty

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
      case PropF.Cont(g)      => PropF.Cont((p, s) => g(p, s).mapResult(f))
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
        val (p, seed) = Prop.startSeed(params)
        next(p, seed).checkOne(Prop.slideSeed(params))
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
      f: (Gen.Parameters, Seed) => PropF[F]
  )(implicit F: MonadError[F, Throwable]): PropF[F] = Cont(f)

  private[effect] case class Cont[F[_]](f: (Gen.Parameters, Seed) => PropF[F])(implicit
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
      extends PropF[F]

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
      genT1: Gen[T1]
  )(
      f: T1 => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty
  ): PropF[F] =
    PropF[F] { (params, seed) =>
      try {
        val r = genT1.doPureApply(params, seed)
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
      arbT1: Arbitrary[T1],
      pp1: T1 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(arbT1.arbitrary)(f)

  def forAllNoShrinkF[F[_], T1, T2, P](
      genT1: Gen[T1],
      genT2: Gen[T2]
  )(
      f: (T1, T2) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty,
      pp2: T2 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(genT1)(t1 => forAllNoShrinkF(genT2)(f(t1, _)))

  def forAllNoShrinkF[F[_], T1, T2, P](
      f: (T1, T2) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      arbT1: Arbitrary[T1],
      pp1: T1 => Pretty,
      arbT2: Arbitrary[T2],
      pp2: T2 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(arbT1.arbitrary, arbT2.arbitrary)(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, P](
      genT1: Gen[T1],
      genT2: Gen[T2],
      genT3: Gen[T3]
  )(
      f: (T1, T2, T3) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: T1 => Pretty,
      pp2: T2 => Pretty,
      pp3: T3 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(genT1)(t1 => forAllNoShrinkF(genT2, genT3)(f(t1, _, _)))

  def forAllNoShrinkF[F[_], T1, T2, T3, P](
      f: (T1, T2, T3) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      arbT1: Arbitrary[T1],
      pp1: T1 => Pretty,
      arbT2: Arbitrary[T2],
      pp2: T2 => Pretty,
      arbT3: Arbitrary[T3],
      pp3: T3 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(arbT1.arbitrary, arbT2.arbitrary, arbT3.arbitrary)(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, P](
      genT1: Gen[T1],
      genT2: Gen[T2],
      genT3: Gen[T3],
      genT4: Gen[T4]
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
    forAllNoShrinkF(genT1)(t1 => forAllNoShrinkF(genT2, genT3, genT4)(f(t1, _, _, _)))

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, P](
      f: (T1, T2, T3, T4) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      arbT1: Arbitrary[T1],
      pp1: T1 => Pretty,
      arbT2: Arbitrary[T2],
      pp2: T2 => Pretty,
      arbT3: Arbitrary[T3],
      pp3: T3 => Pretty,
      arbT4: Arbitrary[T4],
      pp4: T4 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(arbT1.arbitrary, arbT2.arbitrary, arbT3.arbitrary, arbT4.arbitrary)(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, P](
      genT1: Gen[T1],
      genT2: Gen[T2],
      genT3: Gen[T3],
      genT4: Gen[T4],
      genT5: Gen[T5]
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
    forAllNoShrinkF(genT1)(t1 => forAllNoShrinkF(genT2, genT3, genT4, genT5)(f(t1, _, _, _, _)))

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, P](
      f: (T1, T2, T3, T4, T5) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      arbT1: Arbitrary[T1],
      pp1: T1 => Pretty,
      arbT2: Arbitrary[T2],
      pp2: T2 => Pretty,
      arbT3: Arbitrary[T3],
      pp3: T3 => Pretty,
      arbT4: Arbitrary[T4],
      pp4: T4 => Pretty,
      arbT5: Arbitrary[T5],
      pp5: T5 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(
      arbT1.arbitrary,
      arbT2.arbitrary,
      arbT3.arbitrary,
      arbT4.arbitrary,
      arbT5.arbitrary
    )(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, P](
      genT1: Gen[T1],
      genT2: Gen[T2],
      genT3: Gen[T3],
      genT4: Gen[T4],
      genT5: Gen[T5],
      genT6: Gen[T6]
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
    forAllNoShrinkF(genT1)(t1 =>
      forAllNoShrinkF(genT2, genT3, genT4, genT5, genT6)(f(t1, _, _, _, _, _))
    )

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, P](
      f: (T1, T2, T3, T4, T5, T6) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      arbT1: Arbitrary[T1],
      pp1: T1 => Pretty,
      arbT2: Arbitrary[T2],
      pp2: T2 => Pretty,
      arbT3: Arbitrary[T3],
      pp3: T3 => Pretty,
      arbT4: Arbitrary[T4],
      pp4: T4 => Pretty,
      arbT5: Arbitrary[T5],
      pp5: T5 => Pretty,
      arbT6: Arbitrary[T6],
      pp6: T6 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(
      arbT1.arbitrary,
      arbT2.arbitrary,
      arbT3.arbitrary,
      arbT4.arbitrary,
      arbT5.arbitrary,
      arbT6.arbitrary
    )(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, T7, P](
      genT1: Gen[T1],
      genT2: Gen[T2],
      genT3: Gen[T3],
      genT4: Gen[T4],
      genT5: Gen[T5],
      genT6: Gen[T6],
      genT7: Gen[T7]
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
    forAllNoShrinkF(genT1)(t1 =>
      forAllNoShrinkF(genT2, genT3, genT4, genT5, genT6, genT7)(f(t1, _, _, _, _, _, _))
    )

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, T7, P](
      f: (T1, T2, T3, T4, T5, T6, T7) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      arbT1: Arbitrary[T1],
      pp1: T1 => Pretty,
      arbT2: Arbitrary[T2],
      pp2: T2 => Pretty,
      arbT3: Arbitrary[T3],
      pp3: T3 => Pretty,
      arbT4: Arbitrary[T4],
      pp4: T4 => Pretty,
      arbT5: Arbitrary[T5],
      pp5: T5 => Pretty,
      arbT6: Arbitrary[T6],
      pp6: T6 => Pretty,
      arbT7: Arbitrary[T7],
      pp7: T7 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(
      arbT1.arbitrary,
      arbT2.arbitrary,
      arbT3.arbitrary,
      arbT4.arbitrary,
      arbT5.arbitrary,
      arbT6.arbitrary,
      arbT7.arbitrary
    )(f)

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, T7, T8, P](
      genT1: Gen[T1],
      genT2: Gen[T2],
      genT3: Gen[T3],
      genT4: Gen[T4],
      genT5: Gen[T5],
      genT6: Gen[T6],
      genT7: Gen[T7],
      genT8: Gen[T8]
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
    forAllNoShrinkF(genT1)(t1 =>
      forAllNoShrinkF(genT2, genT3, genT4, genT5, genT6, genT7, genT8)(f(t1, _, _, _, _, _, _, _))
    )

  def forAllNoShrinkF[F[_], T1, T2, T3, T4, T5, T6, T7, T8, P](
      f: (T1, T2, T3, T4, T5, T6, T7, T8) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      arbT1: Arbitrary[T1],
      pp1: T1 => Pretty,
      arbT2: Arbitrary[T2],
      pp2: T2 => Pretty,
      arbT3: Arbitrary[T3],
      pp3: T3 => Pretty,
      arbT4: Arbitrary[T4],
      pp4: T4 => Pretty,
      arbT5: Arbitrary[T5],
      pp5: T5 => Pretty,
      arbT6: Arbitrary[T6],
      pp6: T6 => Pretty,
      arbT7: Arbitrary[T7],
      pp7: T7 => Pretty,
      arbT8: Arbitrary[T8],
      pp8: T8 => Pretty
  ): PropF[F] =
    forAllNoShrinkF(
      arbT1.arbitrary,
      arbT2.arbitrary,
      arbT3.arbitrary,
      arbT4.arbitrary,
      arbT5.arbitrary,
      arbT6.arbitrary,
      arbT7.arbitrary,
      arbT8.arbitrary
    )(f)
}
