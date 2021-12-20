ThisBuild / baseVersion := "1.0"

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"

ThisBuild / publishGithubUser := "mpilquist"
ThisBuild / publishFullName := "Michael Pilquist"

ThisBuild / crossScalaVersions := List("3.1.0", "2.12.15", "2.13.7")

ThisBuild / spiewakCiReleaseSnapshots := true

ThisBuild / spiewakMainBranches := List("main")

ThisBuild / homepage := Some(url("https://github.com/typelevel/scalacheck-effect"))

ThisBuild / licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

ThisBuild / scalafmtOnCompile := true

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/typelevel/scalacheck-effect"),
    "git@github.com:typelevel/scalacheck-effect.git"
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core.jvm, core.js, munit.jvm, munit.js)
  .enablePlugins(NoPublishPlugin, SonatypeCiReleasePlugin)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .settings(
    name := "scalacheck-effect",
    Compile / scalacOptions ~= {
      _.filterNot(_ == "-Xfatal-warnings")
    } // we need to turn this off because scalacheck's API uses Stream, which is deprecated
  )
  .settings(
    libraryDependencies ++= List(
      "org.scalacheck" %%% "scalacheck" % "1.15.4",
      "org.typelevel" %%% "cats-core" % "2.7.0"
    )
  )

lazy val munit = crossProject(JSPlatform, JVMPlatform)
  .settings(
    name := "scalacheck-effect-munit",
    testFrameworks += new TestFramework("munit.Framework")
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .dependsOn(core)
  .settings(
    libraryDependencies ++= List(
      "org.scalameta" %%% "munit-scalacheck" % "0.7.29",
      "org.typelevel" %%% "cats-effect" % "3.3.1" % Test
    )
  )
