import sbtcrossproject.CrossPlugin.autoImport.crossProject
import sbtcrossproject.CrossPlugin.autoImport.CrossType

ThisBuild / baseVersion := "0.1"

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"

ThisBuild / publishGithubUser := "mpilquist"
ThisBuild / publishFullName := "Michael Pilquist"

ThisBuild / crossScalaVersions := List("0.26.0", "0.27.0-RC1", "2.12.11", "2.13.3")

ThisBuild / versionIntroduced := Map(
  "0.27.0-RC1" -> "0.1.99" // Disable for now due to bug in sbt-spiewak with RCs
)

ThisBuild / githubWorkflowPublishTargetBranches := Seq(
  RefPredicate.Equals(Ref.Branch("main")),
  RefPredicate.StartsWith(Ref.Tag("v"))
)
ThisBuild / githubWorkflowEnv ++= Map(
  "SONATYPE_USERNAME" -> s"$${{ secrets.SONATYPE_USERNAME }}",
  "SONATYPE_PASSWORD" -> s"$${{ secrets.SONATYPE_PASSWORD }}",
  "PGP_SECRET" -> s"$${{ secrets.PGP_SECRET }}",
  "PGP_PASSPHRASE" -> s"$${{ secrets.PGP_PASSPHRASE }}"
)
ThisBuild / githubWorkflowTargetTags += "v*"

ThisBuild / githubWorkflowPublishPreamble +=
  WorkflowStep.Run(
    List("echo $PGP_SECRET | base64 -d | gpg --import"),
    name = Some("Import signing key"))

ThisBuild / githubWorkflowPublish := Seq(WorkflowStep.Sbt(List("release")))

ThisBuild / homepage := Some(url("https://github.com/typelevel/scalacheck-effect"))

ThisBuild / scmInfo := Some(ScmInfo(url("https://github.com/typelevel/scalacheck-effect"),
  "git@github.com:typelevel/scalacheck-effect.git"))

lazy val root = project.in(file("."))
  .aggregate(core.jvm, core.js, munit.jvm, munit.js)
  .settings(noPublishSettings)

val commonSettings = Seq(
  homepage := Some(url("https://github.com/typelevel/scalacheck-effect")),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),

  libraryDependencies ++= {
    if (isDotty.value) Nil
    else Seq(scalafixSemanticdb)
  },

  scalafmtOnCompile := true
)

lazy val core = crossProject(JSPlatform, JVMPlatform).settings(commonSettings).settings(
  name := "scalacheck-effect",

  libraryDependencies ++= List(
    "org.typelevel"  %%% "cats-core" % "2.2.0",
    "org.scalacheck" %% "scalacheck" % "1.14.3"),

  Compile / scalacOptions ~= { _.filterNot(_ == "-Xfatal-warnings") }   // we need to turn this off because scalacheck's API uses Stream, which is deprecated
)
.settings(dottyLibrarySettings)
.settings(dottyJsSettings(ThisBuild / crossScalaVersions))

lazy val munit = crossProject(JSPlatform, JVMPlatform).settings(commonSettings).settings(
  name := "scalacheck-effect-munit",

  libraryDependencies += "org.typelevel"  %%% "cats-effect" % "2.2.0" % Test,

  testFrameworks += new TestFramework("munit.Framework")
).jsSettings(
  scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
).dependsOn(core)
.settings(dottyLibrarySettings)
.settings(dottyJsSettings(ThisBuild / crossScalaVersions))
.settings(libraryDependencies += "org.scalameta" %%% "munit-scalacheck" % "0.7.12")
