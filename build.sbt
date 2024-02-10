ThisBuild / scalaVersion := "2.13.8"

ThisBuild / scalacOptions ++= Seq(
  "-P:kind-projector:underscore-placeholders"
)

val http4sVersion = "1.0.0-M40"
val fs2Version = "3.9.4"
val tofuVersion = "0.12.0.1"
val circeVersion = "0.14.6"
val catsTaglessVersion = "0.15.0"

enablePlugins(ScalaNativePlugin)

addCompilerPlugin(
  "org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full
)
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

// import to add Scala Native options
import scala.scalanative.build.*

nativeConfig ~= { c =>
  val linkingConfig = if (isLinux) { // brew-installed s2n
    c.withLinkingOptions(
      c.linkingOptions :+ "-L/home/linuxbrew/.linuxbrew/lib"
    )
  } else if (isMacOs) // brew-installed OpenSSL
    if (isArm)
      c.withLinkingOptions(
        c.linkingOptions :+ "-L/opt/homebrew/opt/s2n/lib"
      )
    else
      c.withLinkingOptions(c.linkingOptions :+ "-L/usr/local/opt/s2n/lib")
  else c

  linkingConfig
    .withLTO(LTO.none) // thin
    .withMode(Mode.debug) // releaseFast
    .withGC(GC.immix) // commix

}

envVars ++= {
  Map("S2N_DONT_MLOCK" -> "1")
}

lazy val root = (project in file("."))
  .settings(
    name := "ac-video-downloader",
    libraryDependencies := Seq(
      "org.http4s" %%% "http4s-ember-client" % http4sVersion,
      "org.http4s" %%% "http4s-dsl" % http4sVersion,
      "org.http4s" %%% "http4s-circe" % http4sVersion,
      "io.circe" %%% "circe-generic" % circeVersion,
      "io.circe" %%% "circe-generic-extras" % "0.14.3",
      "io.circe" %%% "circe-parser" % circeVersion,
      "io.circe" %%% "circe-pointer-literal" % circeVersion,
      "org.typelevel" %%% "cats-effect" % "3.5.3",
      "org.typelevel" %%% "cats-core" % "2.9.0",
      "org.typelevel" %%% "cats-free" % "2.10.0",
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.typelevel" %%% "cats-tagless-macros" % catsTaglessVersion,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "co.fs2" %%% "fs2-io" % fs2Version,
      "org.typelevel" %%% "log4cats-core" % "2.6.0",
      "org.typelevel" %% "log4cats-noop" % "2.6.0",
      "com.monovore" %%% "decline" % "2.4.1",
      "com.monovore" %%% "decline-effect" % "2.4.1",
      "com.armanbilge" %%% "epollcat" % "0.1.4"
    )
  )

val isLinux = Option(System.getProperty("os.name"))
  .exists(_.toLowerCase().contains("linux"))
val isMacOs =
  Option(System.getProperty("os.name")).exists(_.toLowerCase().contains("mac"))
val isArm = Option(System.getProperty("os.arch"))
  .exists(_.toLowerCase().contains("aarch64"))
