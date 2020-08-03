import sbtcrossproject.CrossPlugin.autoImport.crossProject
import sbtcrossproject.CrossPlugin.autoImport.CrossType

val commonSettings = Seq(
  organization := "org.typelevel",

  crossScalaVersions := List("2.12.11", "2.13.2", "0.25.0", "0.26.0-RC1"),

  scalacOptions := List("-feature", "-language:higherKinds", "-Xlint:unused", "-Yrangepos"),

  addCompilerPlugin(scalafixSemanticdb),

  scalafmtOnCompile := true
)

lazy val core = crossProject(JSPlatform, JVMPlatform).settings(commonSettings).settings(
  name := "scalacheck-effect",

  libraryDependencies ++= List(
    ("org.typelevel"  %%% "cats-core" % "2.1.1").withDottyCompat(scalaVersion.value),
    ("org.scalacheck" %% "scalacheck" % "1.14.3").withDottyCompat(scalaVersion.value)
  )
)

lazy val munit = crossProject(JSPlatform, JVMPlatform).settings(commonSettings).settings(
  name := "scalacheck-effect-munit",

  libraryDependencies ++= List(
    "org.scalameta" %%% "munit-scalacheck" % "0.7.10",
    ("org.typelevel"  %%% "cats-effect" % "2.1.4" % "test").withDottyCompat(scalaVersion.value)
  ),

  testFrameworks += new TestFramework("munit.Framework")
).dependsOn(core)
