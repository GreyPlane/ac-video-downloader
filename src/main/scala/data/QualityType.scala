package data

import cats.data.ValidatedNel
import cats.implicits._
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto._
import com.monovore.decline.Argument

sealed trait QualityType

object QualityType {

  case object `360p` extends QualityType

  case object `720p` extends QualityType

  case object `1080p` extends QualityType

  case object `1080p+` extends QualityType

  implicit def qualityTypeEncoder: Encoder[QualityType] =
    deriveEnumerationEncoder[QualityType]

  implicit def qualityTypeDecoder: Decoder[QualityType] =
    deriveEnumerationDecoder[QualityType]

  implicit def qualityTypeArgument: Argument[QualityType] =
    new Argument[QualityType] {
      def read(string: String): ValidatedNel[String, QualityType] = {
        string match {
          case "360p" | "360P" =>
            QualityType.`360p`.pure[ValidatedNel[String, *]]
          case "720p" | "720P" =>
            QualityType.`720p`.pure[ValidatedNel[String, *]]
          case "1080p" | "1080P+" =>
            QualityType.`1080p`.pure[ValidatedNel[String, *]]
          case "1080p+" | "1080P+" =>
            QualityType.`1080p+`.pure[ValidatedNel[String, *]]
          case otherwise =>
            s"unknown QualityType $otherwise".invalidNel[QualityType]
        }
      }

      def defaultMetavar: String = "quality type"
    }

}
