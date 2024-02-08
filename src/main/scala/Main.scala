import cats.Monad
import cats.effect._
import cats.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._
import data._
import fs2.io.file.{Files, Path}
import interop._
import io.circe._
import io.circe.generic.auto._
import io.circe.optics.JsonPath.root
import io.circe.parser._
import org.http4s._
import org.http4s.client._
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers._
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object Main
    extends CommandIOApp(
      name = "AC Video Download",
      header = "download video through ac number or album number"
    ) {

  private case class UnexpectedResult(message: String = "")
      extends Exception(message)

  private implicit val loggerFactory: LoggerFactory[IO] =
    Slf4jFactory.create[IO]

  private def extractAlbumInfo(html: String) = {
    val reg = """"contentList":\[.*],""".r

    val contentInfoL = root.contentList.each.as[AlbumContentInfo]

    reg
      .findFirstMatchIn(html)
      .map(_.matched)
      .map(str => s"{${str.dropRight(1)}}")
      .liftTo[Either[Throwable, *]][Throwable](
        UnexpectedResult("content list not found")
      )
      .flatMap(parse)
      .map(contentInfoL.getAll)
  }

  private def extractPageInfo(html: String) = {
    val reg =
      """window.pageInfo = window.videoInfo = (\{(?s).*"priority":\d)""".r

    val ksPlayJsonL = root.currentVideoInfo.ksPlayJson
    val videoInfoL = root.adaptationSet.each.representation.each.as[VideoInfo]
    val transcodeInfoL =
      root.currentVideoInfo.transcodeInfos.each.as[TranscodeInfo]

    val titleL = root.title.string

    def getVideoInfo(pageInfo: Json) = {
      for {
        title <- titleL
          .getOption(pageInfo)
          .liftTo[Either[Throwable, *]](
            UnexpectedResult("title not found")
          )
        kvPlayJson <- ksPlayJsonL
          .as[String]
          .getOption(pageInfo)
          .liftTo[Either[Throwable, *]](
            UnexpectedResult("kvPlayJson not found")
          )
          .flatMap(parse)
        videoInfos <- videoInfoL
          .getAll(kvPlayJson)
          .toNel
          .liftTo[Either[Throwable, *]](
            UnexpectedResult("videoInfos not found")
          )
        transcodeInfos <- transcodeInfoL
          .getAll(pageInfo)
          .toNel
          .liftTo[Either[Throwable, *]](
            UnexpectedResult("transcodeInfos not found")
          )

      } yield PageInfo(title, videoInfos, transcodeInfos)
    }

    reg
      .findFirstMatchIn(html)
      .map(mat => mat.group(1) + "}")
      .liftTo[Either[Throwable, *]](
        UnexpectedResult("Not found raw video info json in HTML")
      )
      .flatMap(parse)
      .flatMap(getVideoInfo)

  }

  private def useIOClient[B](f: Client[IO] => IO[B]): IO[B] = EmberClientBuilder
    .default[IO]
    .withUserAgent(
      `User-Agent`(
        ProductId(
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
      )
    )
    .build
    .use(f)

  private def readCookie(path: Path) = Files[IO]
    .readUtf8(path)
    .compile
    .onlyOrError
    .map(Cookie.parse)
    .flatMap(_.liftTo[IO])

  def main: Opts[IO[ExitCode]] = {
    val outputOpts = Opts.option[Path](
      long = "output",
      help = "downloaded video will be here",
      short = "o"
    )
    val videoAcNumOpts =
      Opts.options[String](
        long = "video",
        help = "ac num of the video",
        short = "v",
        metavar = "ac num"
      )
    val albumAcNumOpts = Opts
      .option[String](
        long = "album",
        help = "ac num of the album",
        short = "a",
        metavar = "ac num"
      )
    val qualityTypeOpts =
      Opts
        .option[QualityType](
          long = "quality",
          help = "video quality",
          short = "q"
        )
        .withDefault(QualityType.`720p`)
    val cookiesOpts = Opts
      .option[Path](
        long = "cookies",
        help = "cookies from website",
        short = "c",
        metavar = "file"
      )
      .withDefault(Path("./cookies"))

    val inputConfigOpts: Opts[InputConfig] = videoAcNumOpts.map(
      InputConfig.Video
    ) orElse albumAcNumOpts.map(InputConfig.Album)

    (
      outputOpts,
      inputConfigOpts,
      qualityTypeOpts,
      cookiesOpts
    ).mapN { case (output, input, qualityType, cookiesPath) =>
      useIOClient { implicit client =>
        readCookie(cookiesPath)
          .flatMap(cookie => {
            val acfun = Acfun[IO](cookie)

            def downloadVideo(ac: String) = for {
              pageHTML <- acfun.getPageHTML(ac)
              PageInfo(title, videoInfos, transcodeInfos) <- extractPageInfo(
                pageHTML
              ).liftTo[IO]
              videoInfo <- videoInfos
                .find(_.qualityType == qualityType)
                .liftTo[IO][Throwable](
                  UnexpectedResult(s"video $ac has no $qualityType available")
                )
              playlist <- acfun.getPlaylist(ac, videoInfo.url)
              done <- acfun.downloadFullVideo(
                title,
                videoInfo.url,
                playlist,
                output,
                qualityType
              )
            } yield done

            def downloadVideos(acs: List[String]) =
              Concurrent[IO].parTraverseN(2)(acs)(downloadVideo) *> IO.unit

            val done = input match {
              case InputConfig.Album(albumAcNum) => {
                for {
                  albumHTML <- acfun.getAlbumHTML(albumAcNum)
                  albumContentInfos <- extractAlbumInfo(albumHTML).liftTo[IO]
                  done <- downloadVideos(
                    albumContentInfos.map(info => s"ac${info.resourceId}")
                  )
                } yield done

              }
              case InputConfig.Video(videoAcNums) => {
                downloadVideos(videoAcNums.toList)
              }
            }

            done *> ExitCode.Success.pure[IO]
          })
      }
    }

  }

}
