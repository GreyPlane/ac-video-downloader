import cats.Functor
import cats.data.NonEmptyList
import cats.effect._
import cats.effect.std.Console
import cats.implicits._
import com.monovore.decline._
import data._
import epollcat.EpollApp
import fs2.io.file.{Files, Path}
import interop._
import org.http4s._
import org.http4s.client._
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers._
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

object Main
    extends EpollApp
//  extends CommandIOApp(
//    name = "AC Video Download",
//    header = "download video through ac number or album number",
//    version = "1.0"
//  )
//
    {

  private def useIOClient[B](f: Client[IO] => IO[B]): IO[B] = {
    implicit val loggerFactory: LoggerFactory[IO] = NoOpFactory.impl[IO]

    EmberClientBuilder
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
  }

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
      useIOClient { implicit client =>
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

  }

  def run(args: List[String]): IO[ExitCode] = {
    val cmd = Command("a", "b", helpFlag = false)(main)

    def printHelp[F[_]: Console: Functor](help: Help): F[ExitCode] =
      Console[F].errorln(help).as {
        if (help.errors.nonEmpty) ExitCode.Error
        else ExitCode.Success
      }

    cmd.parse(args, sys.env) match {
      case Left(help) => printHelp[IO](help)
      case Right(f)   => f
    }
  }
}
