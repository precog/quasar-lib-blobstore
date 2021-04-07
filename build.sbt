import scala.collection.Seq

ThisBuild / scalaVersion := "2.12.11"

publishAsOSSProject in ThisBuild := true

ThisBuild / githubRepository := "quasar-lib-blobstore"

homepage in ThisBuild := Some(url("https://github.com/precog/quasar-lib-blobstore"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/precog/quasar-lib-blobstore"),
  "scm:git@github.com:precog/quasar-lib-blobstore.git"))

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(
  publishArtifact in (Test, packageBin) := true)

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)

val slf4jVersion = "1.7.25"
val specsVersion = "4.8.3"

lazy val core = project
  .in(file("core"))
  .settings(publishTestsSettings)
  .settings(
    name := "quasar-lib-blobstore",
    libraryDependencies ++= Seq(
      "com.precog" %% "async-blobstore-core" % managedVersions.value("precog-async-blobstore"),
      "com.precog" %% "quasar-connector" % managedVersions.value("precog-quasar"),
      "com.precog" %% "quasar-connector" % managedVersions.value("precog-quasar") % Test classifier "tests",
      "com.precog" %% "quasar-foundation" % managedVersions.value("precog-quasar") % Test classifier "tests",
      "org.specs2" %% "specs2-core" % specsVersion % Test,
      "org.specs2" %% "specs2-scalaz" % specsVersion % Test,
      "org.specs2" %% "specs2-scalacheck" % specsVersion % Test,
      "com.codecommit" %% "cats-effect-testing-specs2" % "0.4.0" % Test))
  .evictToLocal("QUASAR_PATH", "connector", true)
  .evictToLocal("QUASAR_PATH", "api", true)

