package data

import cats.data.NonEmptyList
import cats.implicits._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.pointer.literal._

case class AlbumInfo(contentInfos: NonEmptyList[AlbumInfo.ContentInfo])

object AlbumInfo {
  case class ContentInfo(resourceId: Long, title: String)

  def fromAlbumHTML(html: String): Either[Throwable, AlbumInfo] = {
    val reg = """"contentList":\[.*],""".r

    for {
      albumInfoJson <- reg
        .findFirstMatchIn(html)
        .map(_.matched)
        .map(str => s"{${str.dropRight(1)}}")
        .liftTo[Either[Throwable, *]][Throwable](
          UnexpectedResult("content list not found")
        )
        .flatMap(parse)

      contentInfos <- pointer"/contentList"
        .get(albumInfoJson)
        .flatMap(_.as[NonEmptyList[ContentInfo]])

    } yield AlbumInfo(contentInfos)

  }

}
