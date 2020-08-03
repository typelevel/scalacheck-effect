package org.scalacheck.effect

import scala.language.implicitConversions

import cats.MonadError
import cats.implicits._
import org.scalacheck.{Arbitrary, Gen, Prop, Test}
import org.scalacheck.rng.Seed
import org.scalacheck.util.{FreqMap, Pretty}

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

  implicit def effectToPropF[F[_], A](
      fa: F[A]
  )(implicit F: MonadError[F, Throwable]): PropF[F] =
    Eval[F, Result[F]](
      fa.as(Result[F](Prop.True, Nil, Set.empty, Set.empty): PropF[F])
        .handleError(t => Result[F](Prop.Exception(t), Nil, Set.empty, Set.empty))
    )

  def forAllNoShrinkF[F[_], A, P](
      genA: Gen[A]
  )(
      f: A => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable],
      pp1: A => Pretty
  ): PropF[F] =
    PropF[F] { (params, seed) =>
      try {
        val r = genA.doPureApply(params, seed)
        r.retrieve match {
          case Some(a) =>
            val labels = r.labels.mkString(",")
            f(a).addArg(Prop.Arg(labels, a, 0, a, pp1(a), pp1(a))).provedToTrue
          case None => PropF.undecided
        }
      } catch {
        case e: Gen.RetrievalError => PropF.undecided
      }
    }

  def forAllNoShrinkF[F[_], A, P](
      f: A => P
  )(implicit
      toProp: P => PropF[F],
      arbA: Arbitrary[A],
      F: MonadError[F, Throwable]
  ): PropF[F] = {
    forAllNoShrinkF(arbA.arbitrary)(f)
  }

  def forAllNoShrinkF[F[_], A, B, P](
      genA: Gen[A],
      genB: Gen[B]
  )(
      f: (A, B) => P
  )(implicit
      toProp: P => PropF[F],
      F: MonadError[F, Throwable]
  ): PropF[F] = {
    forAllNoShrinkF(genA)(a => forAllNoShrinkF(genB)(b => f(a, b)))
  }

  def forAllNoShrinkF[F[_], A, B, P](
      f: (A, B) => P
  )(implicit
      toProp: P => PropF[F],
      arbA: Arbitrary[A],
      arbB: Arbitrary[B],
      F: MonadError[F, Throwable]
  ): PropF[F] = {
    forAllNoShrinkF(arbA.arbitrary, arbB.arbitrary)(f)
  }
}
