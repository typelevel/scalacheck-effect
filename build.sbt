ThisBuild / tlBaseVersion := "2.0"

ThisBuild / developers += tlGitHubDev("mpilquist", "Michael Pilquist")
ThisBuild / startYear := Some(2021)

ThisBuild / crossScalaVersions := List("3.3.5", "2.12.20", "2.13.16")
ThisBuild / tlVersionIntroduced := Map("3" -> "1.0.2")

ThisBuild / libraryDependencySchemes +=
  "org.scala-native" %% "test-interface_native0.5" % VersionScheme.Always

lazy val root = tlCrossRootProject.aggregate(core, munit)

lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .settings(
    name := "scalacheck-effect",
    tlFatalWarnings := false
  )
  .settings(
    libraryDependencies ++= List(
      "org.scalacheck" %%% "scalacheck" % "1.18.1",
      "org.typelevel" %%% "cats-core" % "2.13.0"
    )
  )

lazy val munit = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .settings(
    name := "scalacheck-effect-munit"
  )
  .dependsOn(core)
  .settings(
    libraryDependencies ++= List(
      "org.scalameta" %%% "munit-scalacheck" % "1.1.0",
      "org.typelevel" %%% "cats-effect" % "3.7.0-RC1" % Test
    )
  )
