package xyz.kd5ujc.schema.fiber

import cats.kernel.{Next, PartialOrder, PartialPrevious}
import cats.{Order, Show}

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.{Decoder, Encoder}

case class FiberOrdinal(value: NonNegLong) {

  def plus(addend: NonNegLong): FiberOrdinal =
    FiberOrdinal(Refined.unsafeApply[Long, NonNegative](value.value + addend.value))
}

object FiberOrdinal {

  val MinValue: FiberOrdinal = FiberOrdinal(NonNegLong.MinValue)

  def apply(value: Long): Option[FiberOrdinal] =
    NonNegLong.from(value).toOption.map(FiberOrdinal(_))

  def unsafeApply(value: Long): FiberOrdinal =
    FiberOrdinal(Refined.unsafeApply[Long, NonNegative](value))

  implicit val order: Order[FiberOrdinal] =
    Order.by(_.value.value)

  implicit val show: Show[FiberOrdinal] =
    Show.show(fo => fo.value.value.toString)

  implicit val next: Next[FiberOrdinal] = new Next[FiberOrdinal] {
    def next(a: FiberOrdinal): FiberOrdinal = unsafeApply(a.value.value + 1L)
    def partialOrder: PartialOrder[FiberOrdinal] = Order[FiberOrdinal]
  }

  implicit val partialPrevious: PartialPrevious[FiberOrdinal] = new PartialPrevious[FiberOrdinal] {
    def partialOrder: PartialOrder[FiberOrdinal] = Order[FiberOrdinal]

    def partialPrevious(a: FiberOrdinal): Option[FiberOrdinal] =
      refineV[NonNegative].apply[Long](a.value.value - 1L).toOption.map(r => FiberOrdinal(r))
  }

  implicit val encoder: Encoder[FiberOrdinal] =
    Encoder[Long].contramap(_.value.value)

  implicit val decoder: Decoder[FiberOrdinal] =
    Decoder[Long].emap(v => NonNegLong.from(v).map(FiberOrdinal(_)).left.map(_.toString))
}
