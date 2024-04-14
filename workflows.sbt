ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / githubWorkflowOSes := Seq("windows-2022")
ThisBuild / githubWorkflowScalaVersions := Seq("2.13.8")
ThisBuild / githubWorkflowTargetTags := Seq("v*")

val libcurlVersion = "7.87.0"
ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    List("sudo apt-get update", "sudo apt-get install libcurl4-openssl-dev"),
    name = Some("Install libcurl (ubuntu)"),
    cond =
      Some("startsWith(matrix.os, 'ubuntu') && matrix.experimental != 'yes'")
  ),
  WorkflowStep.Run(
    List(
      "sudo apt-get update",
      "sudo apt-get purge curl",
      "sudo apt-get install libssl-dev autoconf libtool make wget unzip",
      "cd /usr/local/src",
      s"sudo wget https://curl.se/download/curl-$libcurlVersion.zip",
      s"sudo unzip curl-$libcurlVersion.zip",
      s"cd curl-$libcurlVersion",
      "sudo ./configure --with-openssl --enable-websockets",
      "sudo make",
      "sudo make install",
      "curl-config --version",
      "curl-config --protocols",
      """echo "LD_LIBRARY_PATH=/usr/local/lib/" >> $GITHUB_ENV"""
    ),
    name = Some("Build libcurl from source (ubuntu)"),
    cond =
      Some("startsWith(matrix.os, 'ubuntu') && matrix.experimental == 'yes'")
  ),
  WorkflowStep.Run(
    List(
      "vcpkg integrate install",
      "vcpkg install --triplet x64-windows curl",
      """cp "C:\vcpkg\installed\x64-windows\lib\libcurl.lib" "C:\vcpkg\installed\x64-windows\lib\curl.lib""""
    ),
    name = Some("Install libcurl (windows)"),
    cond = Some("startsWith(matrix.os, 'windows')")
  )
)

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(
    commands = List("ac-video-downloader / nativeLink"),
    name = Some("Build Win32 Artifact")
  )
)

ThisBuild / githubWorkflowBuildPostamble := Seq(
  WorkflowStep.Run(
    commands = List(
      s"mv /ac-video-downloader/native/target/scala-2.13/ac-video-downloader-out.exe ./ac-video-downloader.exe"
    ),
    name = Some("Move Artifact")
  ),
  WorkflowStep.Use(
    ref = UseRef.Public("ncipollo", "release-action", "v1.14.0"),
    params = Map(
      "artifacts" -> "ac-video-downloader.*"
    ),
    name = Some("Release Artifacts"),
    cond = Some("startsWith(github.ref, 'refs/tags/v')")
  )
)

ThisBuild / githubWorkflowGeneratedCI ~= {
  _.map(job =>
    job.id match {
      case "build" =>
        job.copy(permissions = Some(Permissions.WriteAll))
      case _ => job
    }
  )
}

ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty
