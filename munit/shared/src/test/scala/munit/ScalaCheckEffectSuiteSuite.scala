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

package munit

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.effect.PropF
import org.scalacheck.Shrink

// Who tests the tests?
class ScalaCheckEffectSuiteSuite extends ScalaCheckEffectSuite {

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny[T]

  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(munitIOTransform)

  // From https://github.com/scalameta/munit/pull/134
  private val munitIOTransform: ValueTransform =
    new ValueTransform("IO", { case e: IO[_] => e.unsafeToFuture() })

  test("Correctly slides seed for multi-arg PropF") {
    var last: Option[Int] = None
    var duplicates = 0

    PropF.forAllF { (x: Int, y: Int) =>
      if (Some(x) == last) {
        duplicates = duplicates + 1
      }
      last = Some(y)

      // This is a bit of a heuristic as the generator may genuinely produce identical consecutive values
      // The bug was that it wasn't sliding so the first generated value was _always_ equal to the second
      // generated value from the previous iteration
      IO { assert(clue(duplicates) < 10) }
    }

  }

}
