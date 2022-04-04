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
import org.scalacheck.Test.Parameters
import org.scalacheck.effect.PropF

import java.util.concurrent.atomic.AtomicBoolean

class Example extends ScalaCheckEffectSuite {
  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(munitIOTransform)

  // From https://github.com/scalameta/munit/pull/134
  private val munitIOTransform: ValueTransform =
    new ValueTransform("IO", { case e: IO[_] => e.unsafeToFuture() })

  test("one") {
    PropF.forAllNoShrinkF { (a: Int) => IO(assert(a == a)) }
  }

  test("two") {
    PropF.forAllF { (a: Int, b: Int) =>
      IO { assertEquals(a + b, b + a) }
    }
  }

  test("three") {
    PropF.forAllF { (a: Int) =>
      IO { assert( a == a + 1 )}
    }.check(Parameters.default.withMinSuccessfulTests(1))
  }

  val ready = new AtomicBoolean(false)

  val fixture = FunFixture[Unit](
    setup = { _ => ready.set(true) },
    teardown = { _ => ready.set(false) }
  )

  fixture.test("fixture and forall") { _ =>
    PropF.forAllF { (_: Int) =>
      IO { assertEquals(ready.get(), true) }
    }
  }

  // test("three") {
  //   forAllAsyncNoShrink(Arbitrary.arbitrary[Int].filter(i => i > 10 && i < 20)) {
  //     (a: Int) => IO { println(a) }
  //   }
  // }
}
