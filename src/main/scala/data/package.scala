import cats.data.NonEmptyList
import io.circe.{Decoder, DecodingFailure, Encoder}
import org.http4s.Uri
import cats.implicits._
import io.circe.syntax._
import io.circe.generic.semiauto._

package object data {
  case class TranscodeInfo(
      qualityType: QualityType,
      sizeInBytes: Long,
      hdr: Boolean
  )

  case class VideoInfo(
      id: Long,
      url: Uri,
      backupUrl: List[String],
      qualityType: QualityType,
      qualityLabel: String
  )

  object VideoInfo {
    implicit def uriDecoder: Decoder[Uri] = Decoder.instance[Uri] { cursor =>
      cursor
        .as[String]
        .flatMap(str =>
          Uri
            .fromString(str)
            .leftMap(failure => DecodingFailure(failure.message, List.empty))
        )
    }

    implicit def uriEncoder: Encoder[Uri] =
      Encoder.instance[Uri](_.toString().asJson)

    implicit def videoInfoEncoder: Encoder[VideoInfo] = deriveEncoder[VideoInfo]
    implicit def videoInfoDecoder: Decoder[VideoInfo] = deriveDecoder[VideoInfo]
  }

  case class PageInfo(
      title: String,
      videoInfos: NonEmptyList[VideoInfo],
      transcodeInfos: NonEmptyList[TranscodeInfo]
  )

  case class AlbumContentInfo(resourceId: Long, title: String)
}
