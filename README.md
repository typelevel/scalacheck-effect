# ScalaCheck Effect

[![Continuous Integration](https://github.com/typelevel/scalacheck-effect/workflows/Continuous%20Integration/badge.svg)](https://github.com/typelevel/scalacheck-effect/actions?query=workflow%3A%22Continuous+Integration%22)
[![Discord](https://img.shields.io/discord/632277896739946517.svg?label=&logo=discord&logoColor=ffffff&color=404244&labelColor=6A7EC2)](https://discord.gg/XF3CXcMzqD)
[![Latest version](https://index.scala-lang.org/typelevel/scalacheck-effect/scalacheck-effect/latest.svg?color=orange)](https://index.scala-lang.org/typelevel/scalacheck-effect/scalacheck-effect)


ScalaCheck Effect is a library that extends the functionality of [ScalaCheck](https://scalacheck.org) to support "effectful" properties. An effectful property is one that evaluates each sample in some type constructor `F[_]`. For example:

```scala
import org.scalacheck.effect.PropF
import org.scalacheck.Test
import cats.effect.{ExitCode, IO, IOApp}

object Example extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val p: PropF[IO] = 
      PropF.forAllF { (x: Int) =>
        IO(x).map(res => assert(res == x))
      }

    val result: IO[Test.Result] = p.check()

    result.flatMap(r => IO(println(r))).as(ExitCode.Success)
  }
}
```

Running this program results in the output: `Result(Passed,100,0,Map(),0)`.

This library provides the `org.scalacheck.effect.PropF` type, which is the effectul analog to `org.scalacheck.Prop`. In this example, we use `PropF.forAllF` to write a property of the shape `Int => IO[Unit]`. This example uses `cats.effect.IO` as the type constructor, but any effect `F[_]` with an instance of `MonadError[F, Throwable]` can be used, including `scala.concurrent.Future`.

The key idea here is using the `PropF.{forAllF, forAllNoShrinkF}` methods to create `PropF[F]` instances. The `check()` method on `PropF` converts a `PropF[F]` to a `F[Test.Result]`.

## sbt dependency

```scala
libraryDependencies += "org.typelevel" %% "scalacheck-effect" % scalacheckEffectVersion
```

## MUnit Integration

This project also provides support for checking `PropF` values from within [MUnit](https://scalameta.org/munit/) based test suites. To use scalacheck-effect with munit, add the following dependency to your build:

```scala
libraryDependencies += "org.typelevel" %% "scalacheck-effect-munit" % scalacheckEffectVersion % Test
```
The following usage example is for Cats Effect 2:

```scala
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF

// Example uses https://github.com/typelevel/munit-cats-effect

class ExampleSuite extends CatsEffectSuite with ScalaCheckEffectSuite {
  test("first PropF test") {
    PropF.forAllF { (x: Int) =>
      IO(x).start.flatMap(_.join).map(res => assert(res == x))
    }
  }
}
```

For Cats Effect 3, the `join` becomes `joinWithNever`, so the `PropF` in the above example becomes:

```scala
PropF.forAllF { (x: Int) =>
  IO(x).start.flatMap(_.joinWithNever).map(res => assert(res == x))
}
```

For more details on the differences between Cats Effect 2 and 3 see
[the Cats Effect 3.x Migration Guide](https://typelevel.org/cats-effect/docs/migration-guide#exitcase-fiber).

## Design Goals

- Support effectful properties without blocking.
- Compatibility with `Gen`/`Cogen`/`Arbitrary`.
- Parity with `Prop` features, including shrinking.
- Follow same style as ScalaCheck and use ScalaCheck reporting.
- Integrate well with popular test frameworks.
- Non-goal: provide direct support for checking effectful properties directly from SBT or from standalone app.

## Frequently Asked Questions

### Why not just call Await.result / unsafeRunSync inside a property definition?

Calling `Await.result`, `unsafeRunSync()` or a similar blocking operation is not possible on Scala.js.

### How to override the default ScalaCheck test parameters?

By overriding the `scalaCheckTestParameters` from the super `ScalaCheckSuite`.

```scala
override def scalaCheckTestParameters =
  super.scalaCheckTestParameters.withMinSuccessfulTests(20)
```

## Acknowledgements

This library builds heavily on the ideas in ScalaCheck. It grew out of the FS2 [`AsyncPropertySuite`](https://github.com/functional-streams-for-scala/fs2/blob/48f7188ef2df959189f931a7bbb68df4cb81c82a/core/shared/src/test/scala/fs2/AsyncPropertySuite.scala), which only implemented a handful of features. The [Weaver Test](https://disneystreaming.github.io/weaver-test/) framework also has similar support for effectful properties. Finally, the [Scala Hedgehog](https://github.com/hedgehogqa/scala-hedgehog/) library has a [prototype of similar functionality](https://github.com/hedgehogqa/scala-hedgehog/pull/147).
