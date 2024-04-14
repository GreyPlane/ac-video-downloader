//> using dep com.lihaoyi::os-lib::0.9.3

import os._

@main
def genAudio(dir: String): Unit = {
  val targetDirPath = os.Path(dir)
  val files = os.list(targetDirPath)

  val existingAudios = files.filter(_.ext == "m4a").map(_.baseName).toSet
  val untransformedVideos = files
    .filter(_.ext == "mp4")
    .filter(video => !existingAudios.contains(video.baseName))

  untransformedVideos.foreach(video =>
    os.proc(
      "ffmpeg",
      "-i",
      video,
      "-vn",
      "-acodec",
      "copy",
      video.toString.replace("mp4", "m4a")
    ).call()
  )

  untransformedVideos.foreach(println)
}
