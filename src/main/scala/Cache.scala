import cats.MonadThrow
import cats.effect.Async
import cats.implicits._
import fs2.io.file._
import monocle.Iso

trait Cache[F[_]] {
  def getOrLoad[T](key: String, load: () => F[T], iso: Iso[String, T]): F[T]
}

object Cache {

  def apply[F[_]: Async: MonadThrow](tempDirPrefix: String): Cache[F] =
    new FsCache[F](tempDirPrefix)

  private class FsCache[F[_]: Async: MonadThrow](
      tempDirPrefix: String,
      longRunning: Boolean = false
  ) extends Cache[F] {
    def getOrLoad[T](key: String, load: () => F[T], iso: Iso[String, T]): F[T] =
      for {
        tempDir <-
          if (longRunning)
            Files[F].createTempDirectory(None, tempDirPrefix, None)
          else Files[F].currentWorkingDirectory
        filePath = tempDir / key
        ifCached <- Files[F].exists(filePath)
        data <-
          if (ifCached) Files[F].readUtf8(filePath).compile.string.map(iso.get)
          else
            load().flatMap(d =>
              fs2
                .Stream(d)
                .map(iso.reverseGet)
                .through(Files[F].writeUtf8(filePath))
                .compile
                .drain
                .map(Function.const(d))
            )
      } yield data
  }
}
