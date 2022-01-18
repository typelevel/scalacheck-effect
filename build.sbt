ThisBuild / tlBaseVersion := "1.0"

ThisBuild / developers += tlGitHubDev("mpilquist", "Michael Pilquist")

ThisBuild / crossScalaVersions := List("3.1.0", "2.12.15", "2.13.7")

lazy val root = tlCrossRootProject.aggregate(core, munit)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .settings(
    name := "scalacheck-effect",
    tlFatalWarnings := false,
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
  .dependsOn(core)
  .settings(
    libraryDependencies ++= List(
      "org.scalameta" %%% "munit-scalacheck" % "0.7.29",
      "org.typelevel" %%% "cats-effect" % "3.3.0" % Test
    )
  )
