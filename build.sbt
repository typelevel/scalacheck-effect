ThisBuild / tlBaseVersion := "2.0"

ThisBuild / developers += tlGitHubDev("mpilquist", "Michael Pilquist")
ThisBuild / startYear := Some(2021)

ThisBuild / crossScalaVersions := List("3.3.4", "2.12.20", "2.13.15")

lazy val root = tlCrossRootProject.aggregate(core, munit)

lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .settings(
    name := "scalacheck-effect",
    tlVersionIntroduced := Map("3" -> "1.0.2"),
    libraryDependencies ++= List(
      "org.scalacheck" %%% "scalacheck" % "1.17.1",
      "org.typelevel" %%% "cats-core" % "2.11.0"
    )
  )

lazy val munit = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .settings(
    name := "scalacheck-effect-munit",
    testFrameworks += new TestFramework("munit.Framework"),
    tlVersionIntroduced := Map("3" -> "1.0.2"),
    libraryDependencies ++= List(
      "org.scalameta" %%% "munit-scalacheck" % "1.0.0-M11",
      "org.typelevel" %%% "cats-effect" % "3.5.7" % Test
    )
  )
  .dependsOn(core)

lazy val specs2 = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .settings(
    name := "scalacheck-effect-specs2",
    tlVersionIntroduced := Map("3" -> "2.0.0-M3", "2.13" -> "2.0.0-M3", "2.12" -> "2.0.0-M3"),
    startYear := Some(2024),
    libraryDependencies ++= List(
      "org.specs2" %%% "specs2-scalacheck" % "4.20.5",
      "org.typelevel" %%% "cats-effect-testing-specs2" % "1.6.0-2-9144a42-SNAPSHOT",
      "org.typelevel" %%% "cats-effect" % "3.5.7"
    )
  )
  .dependsOn(core)
