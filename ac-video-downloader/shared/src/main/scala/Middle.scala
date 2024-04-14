import cats.data.Tuple2K
import cats.tagless._
import cats.tagless.implicits._
import cats.{Semigroup, ~>}

// From tofu #https://github.com/tofu-tf/tofu
case class Middle[F[_], A](attach: F[A] => F[A]) extends (F[A] => F[A]) {
  override def apply(v1: F[A]): F[A] = attach(v1)
}

object Middle extends MiddleInstances {
  def attach[F[_], Alg[_[_]]: ApplyK](algMid: Alg[Middle[F, *]])(
      alg: Alg[F]
  ): Alg[F] = algMid.map2K(alg)(
    Lambda[Tuple2K[Middle[F, *], F, *] ~> F](t2k =>
      t2k.first.attach(t2k.second)
    )
  )

  implicit final class MiddleAlgebraSyntax[F[_], Alg[_[_]]](
      val algMid: Alg[Middle[F, *]]
  ) extends AnyVal {
    def attach(alg: Alg[F])(implicit apK: ApplyK[Alg]): Alg[F] =
      Middle.attach(algMid)(alg)
  }
}

trait MiddleInstances {
  implicit def middleInvariantK[A]: InvariantK[Middle[*[_], A]] =
    new InvariantK[Middle[*[_], A]] {
      def imapK[F[_], G[_]](
          af: Middle[F, A]
      )(fk: F ~> G)(gk: G ~> F): Middle[G, A] = Middle { ga =>
        fk(af(gk(ga)))
      }
    }

  implicit def middleAlgebraSemigroup[F[_], Alg[_[_]]: ApplyK]
      : Semigroup[Alg[Middle[F, *]]] =
    (x: Alg[Middle[F, *]], y: Alg[Middle[F, *]]) =>
      x.map2K(y)(
        Lambda[Tuple2K[Middle[F, *], Middle[F, *], *] ~> Middle[F, *]](t2k =>
          Middle(fa => t2k.first.attach(t2k.second.attach(fa)))
        )
      )
}
