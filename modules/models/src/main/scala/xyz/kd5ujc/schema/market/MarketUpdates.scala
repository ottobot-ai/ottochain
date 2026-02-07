package xyz.kd5ujc.schema.market

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.fiber.FiberOrdinal

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe._
import io.circe.refined._
import io.circe.syntax._

sealed trait MarketUpdate extends DataUpdate {
  lazy val messageName: String = this.getClass.getSimpleName
  val fiberId: UUID
}

@derive(decoder, encoder)
final case class CreateMarket(
  fiberId:    UUID,
  marketType: MarketType,
  title:      String,
  terms:      JsonLogicValue,
  deadline:   SnapshotOrdinal,
  threshold:  NonNegLong,
  oracles:    Set[Address],
  quorum:     Int
) extends MarketUpdate

@derive(decoder, encoder)
final case class OpenMarket(
  fiberId:              UUID,
  targetSequenceNumber: FiberOrdinal
) extends MarketUpdate

@derive(decoder, encoder)
final case class CommitToMarket(
  fiberId:              UUID,
  amount:               NonNegLong,
  targetSequenceNumber: FiberOrdinal
) extends MarketUpdate

@derive(decoder, encoder)
final case class CloseMarket(
  fiberId:              UUID,
  targetSequenceNumber: FiberOrdinal
) extends MarketUpdate

@derive(decoder, encoder)
final case class SubmitResolution(
  fiberId:              UUID,
  resolution:           JsonLogicValue,
  targetSequenceNumber: FiberOrdinal
) extends MarketUpdate

@derive(decoder, encoder)
final case class FinalizeMarket(
  fiberId:              UUID,
  targetSequenceNumber: FiberOrdinal
) extends MarketUpdate

@derive(decoder, encoder)
final case class ClaimPayout(
  fiberId:              UUID,
  targetSequenceNumber: FiberOrdinal
) extends MarketUpdate

@derive(decoder, encoder)
final case class CancelMarket(
  fiberId:              UUID,
  reason:               String,
  targetSequenceNumber: FiberOrdinal
) extends MarketUpdate

@derive(decoder, encoder)
final case class RefundMarket(
  fiberId:              UUID,
  targetSequenceNumber: FiberOrdinal
) extends MarketUpdate

object MarketUpdate {

  implicit val marketUpdateEncoder: Encoder[MarketUpdate] = {
    case u: CreateMarket     => Json.obj(u.messageName -> u.asJson)
    case u: OpenMarket       => Json.obj(u.messageName -> u.asJson)
    case u: CommitToMarket   => Json.obj(u.messageName -> u.asJson)
    case u: CloseMarket      => Json.obj(u.messageName -> u.asJson)
    case u: SubmitResolution => Json.obj(u.messageName -> u.asJson)
    case u: FinalizeMarket   => Json.obj(u.messageName -> u.asJson)
    case u: ClaimPayout      => Json.obj(u.messageName -> u.asJson)
    case u: CancelMarket     => Json.obj(u.messageName -> u.asJson)
    case u: RefundMarket     => Json.obj(u.messageName -> u.asJson)
  }

  implicit val marketUpdateDecoder: Decoder[MarketUpdate] =
    (c: HCursor) => {
      val decoders = List(
        Decoder[CreateMarket],
        Decoder[OpenMarket],
        Decoder[CommitToMarket],
        Decoder[CloseMarket],
        Decoder[SubmitResolution],
        Decoder[FinalizeMarket],
        Decoder[ClaimPayout],
        Decoder[CancelMarket],
        Decoder[RefundMarket]
      )

      c.keys
        .flatMap(_.headOption)
        .flatMap { field =>
          c.downField(field).success.map { fieldCursor =>
            decoders
              .map(_.tryDecode(fieldCursor))
              .collectFirst { case right @ Right(v) if v.messageName == field => right }
              .getOrElse(Left(DecodingFailure("Cannot decode as MarketUpdate", c.history)))
          }
        }
        .getOrElse(Left(DecodingFailure("Cannot decode as MarketUpdate: JSON is empty", Nil)))
    }
}
