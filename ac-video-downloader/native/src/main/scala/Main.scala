import cats.effect.{ExitCode, IO}
import org.http4s.curl.CurlApp

object Main extends CurlApp {
  def run(args: List[String]): IO[ExitCode] = Application.run(args)(curlClient)
}
