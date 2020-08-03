package munit

import scala.concurrent.Future

import cats.implicits._
import org.scalacheck.Gen
import org.scalacheck.effect.PropF
import org.scalacheck.rng.Seed
import org.scalacheck.util.Pretty

trait ScalaCheckEffectSuite extends ScalaCheckSuite {

  private val initialSeed: Seed =
    scalaCheckTestParameters.initialSeed.getOrElse(
      Seed.fromBase64(scalaCheckInitialSeed).get
    )

  private val genParameters: Gen.Parameters =
    Gen.Parameters.default
      .withLegacyShrinking(scalaCheckTestParameters.useLegacyShrinking)
      .withInitialSeed(initialSeed)

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ scalaCheckPropFTransform

  private val scalaCheckPropFTransform: TestTransform =
    new TestTransform(
      "ScalaCheck PropF",
      t => {
        t.withBodyMap[TestValue](
          _.flatMap {
            case p: PropF[_] =>
              super.munitValueTransform(checkPropF(p)(t.location))
            case r => Future.successful(r)
          }(munitExecutionContext)
        )
      }
    )

  private def checkPropF[F[_]](
      prop: PropF[F]
  )(implicit loc: Location): F[Unit] = {
    import prop.F
    prop.check(scalaCheckTestParameters, genParameters).flatMap { result =>
      if (result.passed) F.unit
      else {
        val seed = genParameters.initialSeed.get
        val seedMessage = s"""|Failing seed: ${seed.toBase64}
                              |You can reproduce this failure by adding the following override to your suite:
                              |
                              |  override val scalaCheckInitialSeed = "${seed.toBase64}"
                              |""".stripMargin
        F.raiseError(fail(seedMessage + "\n" + Pretty.pretty(result, scalaCheckPrettyParameters)))
      }
    }
  }
}
