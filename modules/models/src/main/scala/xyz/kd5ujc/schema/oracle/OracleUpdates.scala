package xyz.kd5ujc.schema.oracle

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.fiber.FiberOrdinal

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe._
import io.circe.refined._
import io.circe.syntax._

sealed trait OracleUpdate extends DataUpdate {
  lazy val messageName: String = this.getClass.getSimpleName
  val fiberId: UUID
}

@derive(decoder, encoder)
final case class RegisterOracle(
  fiberId: UUID,
  address: Address,
  stake:   NonNegLong,
  domains: Set[String]
) extends OracleUpdate

@derive(decoder, encoder)
final case class ActivateOracle(
  fiberId:              UUID,
  targetSequenceNumber: FiberOrdinal
) extends OracleUpdate

@derive(decoder, encoder)
final case class AddStake(
  fiberId:              UUID,
  amount:               NonNegLong,
  targetSequenceNumber: FiberOrdinal
) extends OracleUpdate

@derive(decoder, encoder)
final case class WithdrawStake(
  fiberId:              UUID,
  amount:               NonNegLong,
  targetSequenceNumber: FiberOrdinal
) extends OracleUpdate

@derive(decoder, encoder)
final case class SlashOracle(
  fiberId:              UUID,
  reason:               SlashingReason,
  amount:               NonNegLong,
  targetSequenceNumber: FiberOrdinal
) extends OracleUpdate

@derive(decoder, encoder)
final case class WithdrawOracle(
  fiberId:              UUID,
  targetSequenceNumber: FiberOrdinal
) extends OracleUpdate

object OracleUpdate {

  implicit val oracleUpdateEncoder: Encoder[OracleUpdate] = {
    case u: RegisterOracle => Json.obj(u.messageName -> u.asJson)
    case u: ActivateOracle => Json.obj(u.messageName -> u.asJson)
    case u: AddStake       => Json.obj(u.messageName -> u.asJson)
    case u: WithdrawStake  => Json.obj(u.messageName -> u.asJson)
    case u: SlashOracle    => Json.obj(u.messageName -> u.asJson)
    case u: WithdrawOracle => Json.obj(u.messageName -> u.asJson)
  }

  implicit val oracleUpdateDecoder: Decoder[OracleUpdate] =
    (c: HCursor) => {
      val decoders = List(
        Decoder[RegisterOracle],
        Decoder[ActivateOracle],
        Decoder[AddStake],
        Decoder[WithdrawStake],
        Decoder[SlashOracle],
        Decoder[WithdrawOracle]
      )

      c.keys
        .flatMap(_.headOption)
        .flatMap { field =>
          c.downField(field).success.map { fieldCursor =>
            decoders
              .map(_.tryDecode(fieldCursor))
              .collectFirst { case right @ Right(v) if v.messageName == field => right }
              .getOrElse(Left(DecodingFailure("Cannot decode as OracleUpdate", c.history)))
          }
        }
        .getOrElse(Left(DecodingFailure("Cannot decode as OracleUpdate: JSON is empty", Nil)))
    }
}
