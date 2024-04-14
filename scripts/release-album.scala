//> using dep com.lihaoyi::os-lib::0.9.3

import os._
import java.time._

def isAudio(file: Path): Boolean = file.ext == "m4a"

@main
def releaseAlbum(dir: String): Unit = {
  val targetDirPath = os.Path(dir)
  val things = os.list(targetDirPath)

  val audios = things.filter(isAudio)

  val albumDirs =
    things.filter(os.isDir).filter(_.baseName.startsWith("album_"))

  val releasedAudios =
    albumDirs.flatMap(os.list).filter(isAudio).map(_.baseName)

  val unreleasedAudios =
    audios.filter(audio => !releasedAudios.contains(audio.baseName))

  val now = LocalDateTime.now()
  val monthValue =
    if (now.getMonthValue < 10) s"0${now.getMonthValue}"
    else now.getMonthValue.toString
  val newAlbumName =
    s"album_${now.getYear}${monthValue}${now.getDayOfMonth}"
  val targetAlbumDir = targetDirPath / newAlbumName

  if (!os.exists(targetAlbumDir)) {
    os.makeDir(targetAlbumDir)
  }

  unreleasedAudios.foreach(audio =>
    os.copy.over(audio, targetAlbumDir / s"${audio.baseName}.m4a")
  )

  if (unreleasedAudios.nonEmpty) {
    os.proc(
      "tar",
      "-azcvf",
      targetDirPath / s"amane_$newAlbumName.zip",
      "-C",
      targetDirPath,
      newAlbumName
    ).call()
  }

}