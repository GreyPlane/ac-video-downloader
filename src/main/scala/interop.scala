import cats.data.ValidatedNel
import cats.implicits._
import com.monovore.decline.Argument
import fs2.io.file._

object interop {

  implicit def fs2PathArgument: Argument[Path] = new Argument[Path] {
    def read(string: String): ValidatedNel[String, Path] = {
      Path(string).validNel[String]
    }

    def defaultMetavar: String = "directory"
  }

}
