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
  ): PropF[F] = {
    forAllNoShrinkF(arbT1.arbitrary)(f)
  }

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
  ): PropF[F] = {
    forAllNoShrinkF(genT1)(t1 => forAllNoShrinkF(genT2)(t2 => f(t1, t2)))
  }

  def forAllNoShrinkF[F[_], T1, T2, P](
      f: (T1, T2) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      arbT1: Arbitrary[T1],
      pp1: T1 => Pretty,
      arbT2: Arbitrary[T2],
      pp2: T2 => Pretty
  ): PropF[F] = {
    forAllNoShrinkF(arbT1.arbitrary, arbT2.arbitrary)(f)
  }
}
