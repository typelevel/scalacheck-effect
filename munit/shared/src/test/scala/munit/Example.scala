package munit

import cats.effect.IO
import org.scalacheck.effect.PropF

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

  // test("three") {
  //   forAllAsyncNoShrink(Arbitrary.arbitrary[Int].filter(i => i > 10 && i < 20)) {
  //     (a: Int) => IO { println(a) }
  //   }
  // }
}
