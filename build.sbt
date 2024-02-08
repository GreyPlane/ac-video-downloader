ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.12"

val http4sVersion = "1.0.0-M40"
val fs2Version = "3.9.4"
val tofuVersion = "0.12.0.1"
val circeVersion = "0.14.6"
val enumeratumVersion = "1.7.3"

addCompilerPlugin(
  "org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full
)
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

lazy val root = (project in file("."))
  .settings(
    name := "ac-album-downloader",
    libraryDependencies := Seq(
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.typelevel" %% "cats-core" % "2.9.0",
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-generic-extras" % "0.14.3",
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-optics" % "0.15.0",
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "tf.tofu" %% "tofu-kernel" % tofuVersion,
      "tf.tofu" %% "tofu-core-higher-kind" % tofuVersion,
      "org.typelevel" %% "log4cats-slf4j" % "2.6.0",
      "com.monovore" %% "decline" % "2.4.1",
      "com.monovore" %% "decline-effect" % "2.4.1",
      "io.lindstrom" % "m3u8-parser" % "0.27"
    )
  )
