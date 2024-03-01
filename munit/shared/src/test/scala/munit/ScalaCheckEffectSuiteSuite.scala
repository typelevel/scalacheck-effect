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
