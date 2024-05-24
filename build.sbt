ThisBuild / tlBaseVersion := "2.0"

ThisBuild / developers += tlGitHubDev("mpilquist", "Michael Pilquist")
ThisBuild / startYear := Some(2021)

ThisBuild / crossScalaVersions := List("3.3.3", "2.12.19", "2.13.14")
ThisBuild / tlVersionIntroduced := Map("3" -> "1.0.2")

lazy val root = tlCrossRootProject.aggregate(core, munit)

lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .settings(
    name := "scalacheck-effect",
    tlFatalWarnings := false
  )
  .settings(
    libraryDependencies ++= List(
      "org.scalacheck" %%% "scalacheck" % "1.18.0",
      "org.typelevel" %%% "cats-core" % "2.10.0"
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
      "org.scalameta" %%% "munit-scalacheck" % "1.0.0",
      "org.typelevel" %%% "cats-effect" % "3.5.4" % Test
    )
  )
