/*
 * Copyright 2024 Typelevel
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
package specs2

import cats.*
import cats.effect.testing.UnsafeRun
import cats.effect.testing.specs2.{AsFutureResult, CatsEffect}
import cats.syntax.all.*
import org.scalacheck.effect.PropF.*
import org.scalacheck.rng.Seed
import org.scalacheck.util.{FreqMap, Pretty}
import org.scalacheck.{Gen, Test}
import org.specs2.ScalaCheck
import org.specs2.execute.{Result, *}
import org.specs2.scalacheck.Parameters
import org.specs2.scalacheck.PrettyDetails.collectDetails

import scala.annotation.tailrec
import scala.concurrent.*
import scala.util.control.NonFatal

trait Specs2ScalaCheckEffect extends ScalaCheck { this: CatsEffect =>
  @tailrec
  private def specs2ResultToPropF[F[_]: MonadThrow](
      result: Result
  ): org.scalacheck.effect.PropF[F] =
    result match {
      case Success(_, _)         => passed[F]
      case Skipped(_, _)         => undecided
      case Pending(_)            => undecided
      case Failure(_, _, _, _)   => falsified
      case Error(_, t)           => exception(t)
      case DecoratedResult(_, r) => specs2ResultToPropF(r)
    }

  implicit def propFAsFutureResult[F[_]: Functor: UnsafeRun](implicit
      initialParameters: Parameters,
      prettyFreqMap: FreqMap[Set[Any]] => Pretty
  ): AsFutureResult[PropF[F]] =
    new AsFutureResult[PropF[F]] {
      override def asResult(t: => PropF[F]): Future[Result] = {
        // copied and slightly tweaked from
        // https://github.com/etorreborre/specs2/blob/8a259bf12a2b35d9cd389e607379df740e64c8bd/scalacheck/shared/src/main/scala/org/specs2/scalacheck/ScalaCheckPropertyCheck.scala#L45-L51
        lazy val (parameters, initialSeed) = initialParameters.testParameters.initialSeed match {
          case Some(sd) => (initialParameters, sd)
          case None =>
            val sd = Seed.random()
            (initialParameters.copy(seed = Option(sd)), sd)
        }

        UnsafeRun[F].unsafeToFuture {
          t.check(parameters.testParameters, Gen.Parameters.default.withInitialSeed(initialSeed))
            .map { result =>
              // copied and slightly tweaked from
              // https://github.com/etorreborre/specs2/blob/8a259bf12a2b35d9cd389e607379df740e64c8bd/scalacheck/shared/src/main/scala/org/specs2/scalacheck/ScalaCheckPropertyCheck.scala#L58-L104
              val prettyTestResult = prettyResult(result, parameters, initialSeed, prettyFreqMap)(
                parameters.prettyParams
              )
              val testResult = if (parameters.prettyParams.verbosity == 0) "" else prettyTestResult

              result match {
                case Test.Result(Test.Passed, succeeded, _, _, _) =>
                  Success(prettyTestResult, testResult, succeeded)

                case Test.Result(Test.Proved(_), succeeded, _, _, _) =>
                  Success(prettyTestResult, testResult, succeeded)

                case Test.Result(Test.Exhausted, _, _, _, _) =>
                  Failure(prettyTestResult)

                case Test.Result(Test.Failed(_, _), _, _, fq, _) =>
                  new Failure(prettyTestResult, details = collectDetails(fq)) {
                    // the location is already included in the failure message
                    override def location = ""
                  }

                case Test.Result(Test.PropException(args, ex, labels), _, _, _, _) =>
                  ex match {
                    case FailureException(f) =>
                      // in that case we want to represent a normal failure
                      val failedResult =
                        prettyResult(
                          result.copy(status = Test.Failed(args, labels)),
                          parameters,
                          initialSeed,
                          prettyFreqMap
                        )(parameters.prettyParams)
                      Failure(
                        failedResult + "\n> " + f.message,
                        details = f.details,
                        stackTrace = f.stackTrace
                      )

                    case DecoratedResultException(DecoratedResult(_, f)) =>
                      // in that case we want to represent a normal failure
                      val failedResult =
                        prettyResult(
                          result.copy(status = Test.Failed(args, labels)),
                          parameters,
                          initialSeed,
                          prettyFreqMap
                        )(parameters.prettyParams)
                      f.updateMessage(failedResult + "\n>\n" + f.message)

                    case e: AssertionError =>
                      val failedResult = prettyResult(
                        result.copy(status = Test.Failed(args, labels)),
                        parameters,
                        initialSeed,
                        prettyFreqMap
                      )(parameters.prettyParams)
                      Failure(
                        failedResult + "\n> " + e.getMessage,
                        stackTrace = e.getStackTrace.toList
                      )

                    case SkipException(s)    => s
                    case PendingException(p) => p
                    case NonFatal(t)         => Error(prettyTestResult + showCause(t), t)
                    case _                   => throw ex
                  }
              }
            }
        }
      }
    }

  implicit def effectOfAsResultToPropF[F[_]: MonadThrow, R: AsResult](fu: F[R]): PropF[F] =
    Suspend {
      val asResult = Functor[F].map(fu)(AsResult[R](_))
      Functor[F].map(asResult)(specs2ResultToPropF(_))
    }

}
