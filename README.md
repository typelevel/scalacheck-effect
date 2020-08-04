# ScalaCheck Effect

ScalaCheck Effect is a library that extends the functionality of [ScalaCheck](https://scalacheck.org) to support "effectful" properties. An effectful property is one that evaluates each sample in some type constructor `F[_]`. For example:

```scala
import org.scalacheck.effect.PropF
import org.scalacheck.Test
import cats.effect.{ExitCode, IO, IOApp}

object Example extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val p: PropF[IO] = 
      PropF.forAllNoShrinkF { (x: Int) =>
        IO(x).start.flatMap(_.join).map(res => assert(res == x))
      }

    val result: IO[Test.Result] = p.check()

    result.flatMap(r => IO(println(r))).as(ExitCode.Success)
  }
}
```

Running this program results in the output: `Result(Passed,100,0,Map(),0)`.

This library provides the `org.scalacheck.effect.PropF` type, which is the effectul analog to `org.scalacheck.Prop`. In this example, we use `PropF.forAllNoShrinkF` to write a property of the shape `Int => IO[Unit]`. This example uses `cats.effect.IO` as the type constructor, but any effect `F[_]` with an instance of `MonadError[F, Throwable]` can be used, including `scala.concurrent.Future`.

## MUnit Integration

This project also provides support for checking `PropF` values from within [MUnit](https://scalameta.org/munit/) based test suites.

```scala
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF

// Example assumes https://github.com/scalameta/munit/pull/134 is merged, which adds CatsEffectSuite to munit

class ExampleSuite extends ScalaCheckEffectSuite with CatsEffectSuite {
  test("first PropF test") {
    PropF.forAllNoShrinkF { (x: Int) =>
      IO(x).start.flatMap(_.join).map(res => assert(res == x))
    }
  }
}
```

## Design Goals

- Support effectful properties without blocking.
- Compatibility with `Gen`/`Cogen`/`Arbitrary`.
- Parity with `Prop` features, including shrinking.
- Integrate well with popular test frameworks.
- Non-goal: provide direct support for checking effectful properties directly from SBT or from standalone app.

## Frequently Asked Questions

### Why not just call Await.result / unsafeRunSync inside a property definition?

Calling `Await.result`, `unsafeRunSync()` or a similar blocking operation is not possible on Scala.js.

## Acknowledgements

This library builds heavily on the ideas in ScalaCheck. It grew out of the FS2 [`AsyncPropertySuite`](https://github.com/functional-streams-for-scala/fs2/blob/48f7188ef2df959189f931a7bbb68df4cb81c82a/core/shared/src/test/scala/fs2/AsyncPropertySuite.scala), which only implemented a handful of features. The [Weaver Test](https://disneystreaming.github.io/weaver-test/) framework also has similar support for effectful properties. Finally, the [Scala Hedgehog](https://github.com/hedgehogqa/scala-hedgehog/) library has a [prototype of similar functionality](https://github.com/hedgehogqa/scala-hedgehog/pull/147).
