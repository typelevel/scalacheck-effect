ThisBuild / tlBaseVersion := "2.0"

ThisBuild / developers += tlGitHubDev("mpilquist", "Michael Pilquist")
ThisBuild / startYear := Some(2021)

ThisBuild / crossScalaVersions := List("3.1.3", "2.12.17", "2.13.10")
ThisBuild / tlVersionIntroduced := Map("3" -> "1.0.2")

lazy val root = tlCrossRootProject.aggregate(core, munit)

lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .settings(
    name := "scalacheck-effect",
    tlFatalWarnings := false
  )
  .settings(
    libraryDependencies ++= List(
      "org.scalacheck" %%% "scalacheck" % "1.17.0",
      "org.typelevel" %%% "cats-core" % "2.8.0"
    )
  )

lazy val munit = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .settings(
    name := "scalacheck-effect-munit",
    testFrameworks += new TestFramework("munit.Framework")
  )
  .dependsOn(core)
  .settings(
    libraryDependencies ++= List(
      "org.scalameta" %%% "munit-scalacheck" % "1.0.0-M6",
      "org.typelevel" %%% "cats-effect" % "3.3.14" % Test
    )
  )
