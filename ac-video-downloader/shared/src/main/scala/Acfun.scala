import cats.MonadThrow
import cats.data.{Kleisli, NonEmptyList}
import cats.effect._
import cats.effect.std.Console
import cats.implicits._
import cats.tagless.{ApplyK, Derive}
import data.AlbumInfo.ContentInfo
import data.{AlbumInfo, QualityType, UnexpectedResult}
import fs2.io.file.{Files, Path}
import io.circe.{Json, JsonObject}
import m3u8.MediaPlaylist
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers.{Accept, Cookie, `Content-Type`, `User-Agent`}
import org.http4s.implicits._
import org.typelevel.ci._
import org.http4s.circe._
import io.circe.generic.auto._

trait Acfun[F[_]] {
  def getPageHTML(ac: String): F[String]

  def getAlbumInfo(aa: String): F[AlbumInfo]

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

  private val userAgent = `User-Agent`(
    ProductId(
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )
  )

  private def resolveTideSymbol[F[_]: Async: Files](path: Path): F[Path] = {
    if (path.startsWith("~")) {
      val pathStr = path.toString
      Files[F].userHome.map(_ / Path(pathStr.drop(1)))
    } else {
      path.pure[F]
    }
  }

  private case class AlbumResponse(
      pageCount: Long,
      totalSize: Long,
      contents: List[ContentInfo]
  )

  def apply[F[_]: Async: Concurrent: MonadThrow: Console: Files](
      cookie: Cookie
  )(implicit
      cli: Client[F]
  ): Acfun[F] = {
    val cache: Acfun[Middle[F, *]] = new AcfunCache(Cache[F]("acfun-cache"))
    cache.attach(new Impl[F](cookie))
  }

  private class Impl[F[_]: Async: Concurrent: MonadThrow: Console: Files](
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
          .putHeaders(userAgent)
      }

      request
        .liftTo[F]
        .flatMap(cli.expect[String])
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
            ().pure[F],
            MonadThrow[F].raiseError(
              UnexpectedResult(s"$outputDir is not a directory")
            )
          )
        target = why / fileName
        exists <- Files[F].exists(target)
        pipe = Files[F].writeAll(target)
        done <-
          if (exists)
            Console[F].println(s"$title already downloaded") *> ().pure[F]
          else
            Console[F].println(s"start downloading $title") *> videoStream
              .through(pipe)
              .compile
              .drain *> Console[F].println(s"$title downloaded")
      } yield done
    }

    def getAlbumInfo(aa: String): F[AlbumInfo] = {
      val pageSize = 30

      def albumRestAPIUri =
        uri"https://www.acfun.cn/rest/pc-direct/arubamu/content/list"
          .withQueryParam("size", pageSize)
          .withQueryParam("arubamuId", aa.stripPrefix("aa"))

      def go(
          contents: List[ContentInfo],
          page: Int = 1
      ): F[List[ContentInfo]] = {
        val request = Request[F](
          Method.GET,
          albumRestAPIUri
            .withQueryParam(
              "page",
              page
            )
        )
          .addHeader(cookie)
          .putHeaders(`Content-Type`(MediaType.application.json))
          .putHeaders(userAgent)

        cli.expect(request)(jsonOf[F, AlbumResponse]).flatMap {
          case AlbumResponse(_, totalSize, data) =>
            val totalPage = math.ceil(totalSize.toDouble / pageSize)
            if (page < totalPage) {
              go(contents ++ data, page + 1)
            } else {
              (contents ++ data).pure[F]
            }
        }
      }

      go(List.empty).flatMap(contentInfos =>
        NonEmptyList
          .fromList(contentInfos)
          .map(AlbumInfo.apply)
          .liftTo[F](UnexpectedResult("Empty album"))
      )
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

    def getAlbumInfo(aa: String): Middle[F, AlbumInfo] = Middle { fa => fa }
  }

}
