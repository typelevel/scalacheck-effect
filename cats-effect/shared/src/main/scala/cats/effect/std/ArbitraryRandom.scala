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

package cats.effect.std

import cats.effect.*
import cats.effect.std.Random.ScalaRandom
import cats.syntax.all.*
import org.scalacheck.{Arbitrary, Gen, Shrink}

trait ArbitraryRandom {

  /** Generates a `Random[F]` instance seeded by a generated long value, so that the "randomness"
    * will be repeatable given the same ScalaCheck seed.
    */
  def genRandom[F[_]: Sync]: Gen[Random[F]] =
    Gen.long
      .map(new scala.util.Random(_).pure[F])
      .map(new ScalaRandom[F](_) {})

  implicit def arbRandom[F[_]: Sync]: Arbitrary[Random[F]] = Arbitrary(genRandom[F])

  implicit def shrinkRandom[F[_]]: Shrink[Random[F]] = Shrink.shrinkAny
}

object ArbitraryRandom extends ArbitraryRandom
