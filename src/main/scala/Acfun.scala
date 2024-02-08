import cats.effect._
import cats.effect.std.Console
import cats.implicits._
import cats.tagless.{ApplyK, Derive}
import cats.{Monad, MonadThrow}
import data.QualityType
import fs2.io.file.{Files, Path}
import io.lindstrom.m3u8.model._
import io.lindstrom.m3u8.parser.MediaPlaylistParser
import monocle.Iso
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers.{Accept, Cookie}
import org.typelevel.ci.CIStringSyntax
import tofu.higherKind.Mid

import scala.util.Try

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

  private val m3u8Parser = new MediaPlaylistParser()
  private val iso = Iso[String, MediaPlaylist](m3u8Parser.readPlaylist)(
    m3u8Parser.writePlaylistAsString
  )

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
    val cache: Acfun[Mid[F, *]] = new AcfunCache(Cache[F]("acfun-cache"))
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
        .flatMap(m3u8Data => Try(m3u8Parser.readPlaylist(m3u8Data)).liftTo[F])
    }

    def downloadFullVideo(
        title: String,
        mainPageUrl: Uri,
        playlist: MediaPlaylist,
        outputDir: Path,
        qualityType: QualityType
    ): F[Unit] = {
      import scala.jdk.CollectionConverters._

      def constructSegmentUrl(segment: MediaSegment): Uri = {
        val partial = Uri.fromString(segment.uri()).toOption.get
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

      val videoStream = playlist
        .mediaSegments()
        .asScala
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
              new IllegalArgumentException(s"$outputDir is not a directory")
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

  private class AcfunCache[F[_]: Async](cache: Cache[F])
      extends Acfun[Mid[F, *]] {

    def getPageHTML(ac: String): Mid[F, String] = { fa =>
      cache.getOrLoad[String](ac + "-page", () => fa, Iso.id)
    }

    def getPlaylist(ac: String, url: Uri): Mid[F, MediaPlaylist] = { fa =>
      cache.getOrLoad(ac + "-m3u8", () => fa, iso)
    }

    def downloadFullVideo(
        title: String,
        mainPageUrl: Uri,
        playlist: MediaPlaylist,
        outputDir: Path,
        qualityType: QualityType
    ): Mid[F, Unit] = { fa => fa }

    def getAlbumHTML(aa: String): Mid[F, String] = { fa =>
      cache.getOrLoad(aa + "-album", () => fa, Iso.id)
    }
  }

}
