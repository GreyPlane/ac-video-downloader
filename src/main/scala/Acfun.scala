import cats.data.Kleisli
import cats.effect._
import cats.effect.std.Console
import cats.implicits._
import cats.tagless.{ApplyK, Derive}
import cats.{Monad, MonadThrow}
import data.{QualityType, UnexpectedResult}
import fs2.io.file.{Files, Path}
import m3u8.MediaPlaylist
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers.{Accept, Cookie}
import org.typelevel.ci.CIStringSyntax

trait Acfun[F[_]] {
  def getPageHTML(ac: String): F[String]

  def getAlbumHTML(aa: String): F[String]

  def getPlaylist(ac: String, url: Uri): F[MediaPlaylist]

  def downloadFullVideo(
      title: String,
      mainPageUrl: Uri,
      playlist: MediaPlaylist,
      outputDir: Path,
      qualityType: QualityType
  ): F[Unit]
}

object Acfun {
  private implicit def applyK: ApplyK[Acfun] = Derive.applyK[Acfun]

  private def resolveTideSymbol[F[_]: Async](path: Path): F[Path] = {
    if (path.startsWith("~")) {
      val pathStr = path.toString
      Files[F].userHome.map(_ / Path(pathStr.drop(1)))
    } else {
      path.pure[F]
    }
  }

  def apply[F[_]: Async: Concurrent: MonadThrow: Console](cookie: Cookie)(
      implicit cli: Client[F]
  ): Acfun[F] = {
    val cache: Acfun[Middle[F, *]] = new AcfunCache(Cache[F]("acfun-cache"))
    cache.attach(new Impl[F](cookie))
  }

  private class Impl[F[_]: Async: Concurrent: MonadThrow: Console](
      cookie: Cookie
  )(implicit
      cli: Client[F]
  ) extends Acfun[F] {
    def getPageHTML(ac: String): F[String] = {
      val request = for {
        uri <- Uri.fromString(s"https://www.acfun.cn/v/$ac")
      } yield {
        Request[F](Method.GET, uri)
          .addHeader(cookie)
          .addHeader(Accept(MediaRange.`text/*`))
      }

      request.liftTo[F].flatMap(cli.expect[String])
    }

    def getPlaylist(ac: String, url: Uri): F[MediaPlaylist] = {
      cli
        .expect[String](url)
        .flatMap(MediaPlaylist.parseF[F])
    }

    def downloadFullVideo(
        title: String,
        mainPageUrl: Uri,
        playlist: MediaPlaylist,
        outputDir: Path,
        qualityType: QualityType
    ): F[Unit] = {

      def constructSegmentUrl(segment: m3u8.Segment): Uri = {
        val partial = Uri.fromString(segment.uri).toOption.get
        val path =
          mainPageUrl.path
            .splitAt(mainPageUrl.path.segments.length - 1)
            ._1
            .concat(partial.path)
        partial.copy(
          scheme = mainPageUrl.scheme,
          authority = mainPageUrl.authority,
          path = path
        )
      }

      def requestVideoSegment(url: Uri) = cli
        .stream(Request(Method.GET, url))
        .flatMap(_.body)

      val videoStream = playlist.segments
        .map(constructSegmentUrl)
        .toList
        .map(requestVideoSegment)
        .reduce(_ ++ _)

      val fileName = s"$title-${qualityType.toString.toUpperCase}.mp4"

      for {
        why <- resolveTideSymbol(outputDir)
        _ <- Files[F]
          .isDirectory(why)
          .ifM(
            Monad[F].pure(),
            MonadThrow[F].raiseError(
              UnexpectedResult(s"$outputDir is not a directory")
            )
          )
        target = why / fileName
        exists <- Files[F].exists(target)
        pipe = Files[F].writeAll(target)
        done <-
          if (exists)
            Console[F].println(s"$title already downloaded") *> Monad[F].pure()
          else
            Console[F].println(s"start downloading $title") *> videoStream
              .through(pipe)
              .compile
              .drain *> Console[F].println(s"$title downloaded")
      } yield done
    }

    def getAlbumHTML(aa: String): F[String] = {
      val request = for {
        uri <- Uri.fromString(s"https://www.acfun.cn/a/$aa")
      } yield {
        Request[F](Method.GET, uri)
          .addHeader(cookie)
          .putHeaders(
            Header.Raw(
              ci"accept",
              "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0."
            )
          )
      }

      request.liftTo[F].flatMap(cli.expect[String])
    }
  }

  private class AcfunCache[F[_]: Async: MonadThrow](cache: Cache[F])
      extends Acfun[Middle[F, *]] {

    def getPageHTML(ac: String): Middle[F, String] = Middle { fa =>
      cache.getOrLoad(
        ac + "-page",
        () => fa,
        Kleisli { _.pure[F] },
        Kleisli { _.pure[F] }
      )
    }

    def getPlaylist(ac: String, url: Uri): Middle[F, MediaPlaylist] = Middle {
      fa =>
        cache.getOrLoad(
          ac + "-m3u8",
          () => fa,
          Kleisli(MediaPlaylist.parseF[F]),
          Kleisli.fromFunction[F, MediaPlaylist](_.show)
        )
    }

    def downloadFullVideo(
        title: String,
        mainPageUrl: Uri,
        playlist: MediaPlaylist,
        outputDir: Path,
        qualityType: QualityType
    ): Middle[F, Unit] = Middle { fa => fa }

    def getAlbumHTML(aa: String): Middle[F, String] = Middle { fa =>
      cache.getOrLoad(
        aa + "-album",
        () => fa,
        Kleisli { _.pure[F] },
        Kleisli { _.pure[F] }
      )
    }
  }

}
