import cats.MonadThrow
import cats.data.Kleisli
import cats.effect.Async
import cats.implicits._
import fs2.io.file._

trait Cache[F[_]] {
  def getOrLoad[T](
      key: String,
      load: () => F[T],
      from: Kleisli[F, String, T],
      to: Kleisli[F, T, String]
  ): F[T]
}

object Cache {

  def apply[F[_]: Async: MonadThrow](tempDirPrefix: String): Cache[F] =
    new FsCache[F](tempDirPrefix)

  private class FsCache[F[_]: Async: MonadThrow](
      tempDirPrefix: String,
      longRunning: Boolean = false
  ) extends Cache[F] {
    def getOrLoad[T](
        key: String,
        load: () => F[T],
        from: Kleisli[F, String, T],
        to: Kleisli[F, T, String]
    ): F[T] =
      for {
        tempDir <-
          if (longRunning)
            Files[F].createTempDirectory(None, tempDirPrefix, None)
          else Files[F].currentWorkingDirectory
        filePath = tempDir / key
        ifCached <- Files[F].exists(filePath)
        data <-
          if (ifCached)
            Files[F].readUtf8(filePath).compile.string.flatMap(from.run)
          else
            load().flatMap(d =>
              fs2
                .Stream(d)
                .evalMap(to.run)
                .through(Files[F].writeUtf8(filePath))
                .compile
                .drain
                .map(Function.const(d))
            )
      } yield data
  }
}
