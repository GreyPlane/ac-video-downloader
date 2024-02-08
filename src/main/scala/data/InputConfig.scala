package data

import cats.data.NonEmptyList

sealed trait InputConfig

object InputConfig {
  final case class Album(acNum: String) extends InputConfig

  final case class Video(acNums: NonEmptyList[String]) extends InputConfig
}
