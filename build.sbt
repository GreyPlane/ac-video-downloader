val http4sVersion = "0.23.26"
val fs2Version = "3.10-365636d"
val circeVersion = "0.14.6"
val catsTaglessVersion = "0.14.0"
val catsEffectVersion = "3.6-c7ca678"

val vcpkgBaseDir = "C:/vcpkg/"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation"
)
ThisBuild / javacOptions ++= Seq(
  "-Xmx2048"
)

logLevel := Level.Info

// import to add Scala Native options
import scala.scalanative.build._

lazy val `ac-video-downloader` = crossProject(JVMPlatform, NativePlatform)
  .settings(
    scalaVersion := "2.13.13",
    libraryDependencies ++= Seq(
      "com.monovore" %%% "decline" % "2.4.1",
      "org.http4s" %%% "http4s-dsl" % http4sVersion,
      "org.http4s" %%% "http4s-circe" % http4sVersion,
      "org.http4s" %%% "http4s-ember-client" % http4sVersion,
      "org.http4s" %%% "http4s-circe" % http4sVersion,
      "io.circe" %%% "circe-generic" % circeVersion,
      "io.circe" %%% "circe-generic-extras" % "0.14.3",
      "io.circe" %%% "circe-parser" % circeVersion,
      "io.circe" %%% "circe-pointer-literal" % circeVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "org.typelevel" %%% "cats-core" % "2.9.0",
      "org.typelevel" %%% "cats-tagless-core" % catsTaglessVersion,
      "org.typelevel" %%% "cats-tagless-macros" % catsTaglessVersion,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "co.fs2" %%% "fs2-io" % fs2Version,
      "org.typelevel" %%% "log4cats-core" % "2.6.0",
      "org.typelevel" %%% "log4cats-noop" % "2.6.0"
    ),
    addCompilerPlugin(
      "org.typelevel" % "kind-projector" % "0.13.3" cross CrossVersion.full
    ),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
  )
  .nativeSettings( // defaults set with common options shown
    libraryDependencies ++= Seq("org.http4s" %%% "http4s-curl" % "0.2.0"),
    nativeConfig ~= { c =>
      val vcpkgBaseDir = "C:/vcpkg/"
      val osNameOpt = sys.props.get("os.name")
      val isLinux = osNameOpt.exists(_.toLowerCase().contains("linux"))
      val isMacOs = osNameOpt.exists(_.toLowerCase().contains("mac"))
      val isArm =
        sys.props.get("os.arch").exists(_.toLowerCase().contains("aarch64"))
      val isWindows = osNameOpt.exists(_.toLowerCase().contains("windows"))
      val platformOptions =
        if (isLinux)
          c.withLinkingOptions(
            c.linkingOptions :+ "-L/home/linuxbrew/.linuxbrew/lib"
          )
        else if (isMacOs) { // brew-installed curl
          if (isArm)
            c.withLinkingOptions(
              c.linkingOptions :+ "-L/opt/homebrew/opt/curl/lib"
            )
          else
            c.withLinkingOptions(
              c.linkingOptions :+ "-L/usr/local/opt/curl/lib"
            )
        } else if (isWindows) { // vcpkg-installed curl
          c.withCompileOptions(
            c.compileOptions :+ s"-I${vcpkgBaseDir}/installed/x64-windows/include/"
          ).withLinkingOptions(
            c.linkingOptions :+ s"-L${vcpkgBaseDir}/installed/x64-windows/lib/"
          )
        } else c

      platformOptions
        .withLTO(LTO.none) // thin
        .withGC(GC.none)
        .withTargetTriple("x86_64-pc-windows-msvc19.20.0")
//        .withTargetTriple("arm64-apple-macosx14.0.0")
        .withMode(Mode.release)
    },
    envVars ++= {
      if (sys.props.get("os.name").exists(_.toLowerCase().contains("windows")))
        Map(
          "PATH" -> s"${sys.props.getOrElse("PATH", "")};${vcpkgBaseDir}/installed/x64-windows/bin/"
        )
      else Map.empty[String, String]
    }
  )
  .jvmSettings(fork := true)
