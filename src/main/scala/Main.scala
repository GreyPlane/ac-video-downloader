import cats.data.NonEmptyList
import cats.effect._
import cats.effect.std.Console
import cats.implicits._
import com.monovore.decline._
import data._
import fs2.io.file.{Files, Path}
import interop._
import org.http4s.client.Client
import org.http4s.curl.CurlApp
import org.http4s.headers._

object Main extends CurlApp {

  private def readCookie(path: Path) = Files[IO]
    .readUtf8(path)
    .compile
    .onlyOrError
    .map(Cookie.parse)
    .flatMap(_.liftTo[IO])

  private def main: Opts[IO[ExitCode]] = {
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
    val parallelismOpts = Opts
      .option[Int](
        long = "parallelism",
        help = "maximum parallelism of downloading",
        short = "p"
      )
      .withDefault(2)
      .validate("Do NOT set this number too high!")(_ < 5)

    val inputConfigOpts: Opts[InputConfig] = videoAcNumOpts.map(
      InputConfig.Video
    ) orElse albumAcNumOpts.map(InputConfig.Album)

    (
      outputOpts,
      inputConfigOpts,
      qualityTypeOpts,
      cookiesOpts,
      parallelismOpts
    ).mapN { case (output, input, qualityType, cookiesPath, parallelism) =>
      implicit val ioClient: Client[IO] = curlClient

      readCookie(cookiesPath)
        .flatMap(cookie => {
          val acfun = Acfun[IO](cookie)

          def downloadVideo(ac: String) = for {
            pageHTML <- acfun.getPageHTML(ac)
            PageInfo(title, videoInfos, _) <- PageInfo
              .fromPageHTML(pageHTML)
              .liftTo[IO]
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

          def downloadVideos(acs: NonEmptyList[String]) =
            Concurrent[IO].parTraverseN(parallelism)(acs)(
              downloadVideo
            ) *> IO.unit

          val done = input match {
            case InputConfig.Album(albumAcNum) => {
              for {
                albumHTML <- acfun.getAlbumHTML(albumAcNum)
                AlbumInfo(contentInfos) <- AlbumInfo
                  .fromAlbumHTML(albumHTML)
                  .liftTo[IO]
                done <- downloadVideos(
                  contentInfos.map(info => s"ac${info.resourceId}")
                )
              } yield done

            }
            case InputConfig.Video(videoAcNums) => {
              downloadVideos(videoAcNums)
            }
          }

          done *> ExitCode.Success.pure[IO]
        })
    }

  }

  def run(args: List[String]): IO[ExitCode] = {
    val cmd = Command(
      "AC Video Download",
      "download video through ac number or album number",
      helpFlag = false
    )(main)

    def printHelp(help: Help): IO[ExitCode] =
      Console[IO].errorln(help).as {
        if (help.errors.nonEmpty) ExitCode.Error
        else ExitCode.Success
      }

    cmd.parse(args, sys.env).fold(printHelp, identity)
  }
}
