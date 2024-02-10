package data

import cats.data.NonEmptyList
import cats.implicits._
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.pointer.literal._

case class PageInfo(
    title: String,
    videoInfos: NonEmptyList[VideoInfo],
    transcodeInfos: NonEmptyList[TranscodeInfo]
)

object PageInfo {
  def fromPageHTML(html: String): Either[Throwable, PageInfo] = {
    val reg =
      """window.pageInfo = window.videoInfo = (\{(?s).*"priority":\d)""".r

    for {
      videoInfoJson <- reg
        .findFirstMatchIn(html)
        .map(mat => mat.group(1) + "}")
        .liftTo[Either[Throwable, *]](
          UnexpectedResult("Not found raw video info json in HTML")
        )
        .flatMap(parse)

      title <- pointer"/title".get(videoInfoJson).flatMap(_.as[String])
      kvPlayJson <- pointer"/currentVideoInfo/ksPlayJson"
        .get(videoInfoJson)
        .flatMap(_.as[String])
        .flatMap(parse)
      adaptationSets <- pointer"/adaptationSet"
        .get(kvPlayJson)
        .flatMap(k => k.as[NonEmptyList[Json]])
      videoInfos <- adaptationSets.flatTraverse(adaptationSet =>
        pointer"/representation"
          .get(adaptationSet)
          .flatMap(_.as[NonEmptyList[VideoInfo]])
      )
      transcodeInfos <- pointer"/currentVideoInfo/transcodeInfos"
        .get(videoInfoJson)
        .flatMap(_.as[NonEmptyList[TranscodeInfo]])

    } yield PageInfo(title, videoInfos, transcodeInfos)

  }
}
