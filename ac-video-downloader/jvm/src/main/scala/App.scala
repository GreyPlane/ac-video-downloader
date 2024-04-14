import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.ember.client.EmberClientBuilder

object App extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    EmberClientBuilder
      .default[IO]
      .build
      .use(implicit client => Application.run(args))
  }
}
