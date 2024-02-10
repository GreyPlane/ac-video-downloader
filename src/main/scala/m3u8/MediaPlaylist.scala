package m3u8

import cats.{MonadThrow, Show}
import cats.data.NonEmptyList
import cats.implicits._
import data.UnexpectedResult

case class MediaPlaylist(segments: NonEmptyList[Segment])

object MediaPlaylist {
  def parse(raw: String): Either[cats.parse.Parser.Error, MediaPlaylist] = {
    Parser.mediaPlaylist.parseAll(raw)
  }

  def parseF[F[_]: MonadThrow](raw: String): F[MediaPlaylist] = {
    parse(raw).leftMap(error => UnexpectedResult(error.show)).liftTo[F]
  }

  def unsafeParse(raw: String): MediaPlaylist = {
    Parser.mediaPlaylist.parseAll(raw).toOption.get
  }

  implicit val show: Show[MediaPlaylist] = (t: MediaPlaylist) => {
    s"""#EXTM3U
       |#EXT-X-VERSION:3
       |#EXT-X-TARGETDURATION:5
       |#EXT-X-MEDIA-SEQUENCE:0
       |${t.segments.mkString_("\n")}
       |#EXT-X-ENDLIST""".stripMargin
  }

  private object Parser {
    import cats.parse.Parser._
    import cats.parse.{Numbers, Parser, Rfc5234}

    val eol = Rfc5234.cr.orElse(Rfc5234.lf)
    def tag[A](
        nameP: Parser[String],
        valueP: Parser[A]
    ): Parser[Tag[A]] =
      (nameP ~ char(':') ~ valueP).map { case ((name, _), value) =>
        Tag(name, Some(value))
      }

    def tag[A](
        nameP: Parser[String]
    ): Parser[Tag[A]] =
      nameP.map(Tag(_, None))

    val extm3u = tag(string("#EXTM3U").string)

    val extinf = tag(string("#EXTINF").string, Numbers.jsonNumber)

    val extversion = tag(string("#EXT-X-VERSION").string, Numbers.jsonNumber)

    val exttargetduration =
      tag(string("#EXT-X-TARGETDURATION").string, Numbers.jsonNumber)

    val extmediasequence =
      tag(string("#EXT-X-MEDIA-SEQUENCE").string, Numbers.jsonNumber)

    val endlist = tag(string("#EXT-X-ENDLIST").string)

    // to complex to parse uri correctly
    val uri = until(eol | endlist).repAs[String]

    val segment = (extinf ~ char(',') ~ eol ~ uri).map {
      case (((tag, _), _), uri) =>
        Segment(tag, uri)
    }

    val mediaPlaylist =
      (extm3u ~ eol ~ extversion ~ eol ~ exttargetduration ~ eol ~ extmediasequence ~ eol ~ segment
        .repSep(eol) ~ eol ~ endlist ~ anyChar.rep0).map {
        case ((((_, segments), _), _), _) =>
          MediaPlaylist(segments)
      }

  }

}

case class Segment(extInf: Tag[String], uri: String)

object Segment {

  implicit val show: Show[Segment] = (t: Segment) =>
    s"""#EXTINF:${t.extInf.value.get},
       |${t.uri}""".stripMargin

}

case class Tag[T](name: String, value: Option[T])
