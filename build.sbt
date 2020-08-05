import sbtcrossproject.CrossPlugin.autoImport.crossProject
import sbtcrossproject.CrossPlugin.autoImport.CrossType

crossScalaVersions in ThisBuild := List("2.12.11", "2.13.2", "0.25.0", "0.26.0-RC1")

githubWorkflowPublishTargetBranches in ThisBuild := Seq(RefPredicate.Equals(Ref.Branch("main")))
githubWorkflowEnv in ThisBuild ++= Map(
  "SONATYPE_USERNAME" -> s"$${{ secrets.SONATYPE_USERNAME }}",
  "SONATYPE_PASSWORD" -> s"$${{ secrets.SONATYPE_PASSWORD }}"
)

val commonSettings = Seq(
  organization := "org.typelevel",
  homepage := Some(url("https://github.com/typelevel/scalacheck-effect")),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      "mpilquist",
      "Michael Pilquist",
      "mpilquist@gmail.com",
      url("https://github.com/mpilquist")
    )
  ),

  scalacOptions ++= List("-feature", "-language:higherKinds,implicitConversions") ++
    (scalaBinaryVersion.value match {
      case v if v.startsWith("2.13") => List("-Xlint", "-Yrangepos")
      case v if v.startsWith("2.12") => Nil
      case v if v.startsWith("0.") => Nil
      case other => sys.error(s"Unsupported scala version: $other")
    }),

  libraryDependencies ++= {
    if (isDotty.value) Nil
    else Seq(scalafixSemanticdb)
  },

  crossScalaVersions := {
    val default = crossScalaVersions.value
    if (crossProjectPlatform.value.identifier != "jvm")
      default.filter(_.startsWith("2."))
    else
      default
  },

  scalafmtOnCompile := true,

  Compile / doc / sources := {
    val old = (Compile / doc / sources).value
    if (isDotty.value)
      Seq()
    else
      old
  },
  Test / doc / sources := {
    val old = (Test / doc / sources).value
    if (isDotty.value)
      Seq()
    else
      old
  }
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
).jsSettings(
  scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
).dependsOn(core)
