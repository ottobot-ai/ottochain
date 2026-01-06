package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax.EncoderOps

object Updates {

  sealed trait OttochainMessage extends DataUpdate {
    lazy val messageName: String = this.getClass.getSimpleName
    val cid: UUID
  }

  sealed trait StateMachineFiberOp

  @derive(decoder, encoder)
  final case class CreateStateMachineFiber(
    cid:           UUID,
    definition:    StateMachine.StateMachineDefinition,
    initialData:   JsonLogicValue,
    parentFiberId: Option[UUID] = None
  ) extends StateMachineFiberOp
      with OttochainMessage

  @derive(decoder, encoder)
  final case class ProcessFiberEvent(
    cid:   UUID,
    event: StateMachine.Event
  ) extends StateMachineFiberOp
      with OttochainMessage

  @derive(decoder, encoder)
  final case class ArchiveFiber(
    cid: UUID
  ) extends StateMachineFiberOp
      with OttochainMessage

  sealed trait ScriptOracleFiberOp

  @derive(decoder, encoder)
  final case class CreateScriptOracle(
    cid:           UUID,
    scriptProgram: JsonLogicExpression,
    initialState:  Option[JsonLogicValue],
    accessControl: Records.AccessControlPolicy
  ) extends ScriptOracleFiberOp
      with OttochainMessage

  @derive(decoder, encoder)
  final case class InvokeScriptOracle(
    cid:    UUID,
    method: String,
    args:   JsonLogicValue
  ) extends ScriptOracleFiberOp
      with OttochainMessage

  object OttochainMessage {

    implicit val messageEncoder: Encoder[OttochainMessage] = {
      case u: Updates.CreateStateMachineFiber => Json.obj(u.messageName -> u.asJson)
      case u: Updates.ProcessFiberEvent       => Json.obj(u.messageName -> u.asJson)
      case u: Updates.ArchiveFiber            => Json.obj(u.messageName -> u.asJson)
      case u: Updates.CreateScriptOracle      => Json.obj(u.messageName -> u.asJson)
      case u: Updates.InvokeScriptOracle      => Json.obj(u.messageName -> u.asJson)
    }

    implicit val messageDecoder: Decoder[OttochainMessage] =
      (c: HCursor) => {
        val decoders = List(
          Decoder[Updates.CreateStateMachineFiber],
          Decoder[Updates.ProcessFiberEvent],
          Decoder[Updates.ArchiveFiber],
          Decoder[Updates.CreateScriptOracle],
          Decoder[Updates.InvokeScriptOracle]
        )

        c.keys
          .flatMap(_.headOption)
          .flatMap { field =>
            c.downField(field).success.map { fieldCursor =>
              decoders
                .map(_.tryDecode(fieldCursor))
                .collectFirst { case right @ Right(v) if v.messageName == field => right }
                .getOrElse(Left(DecodingFailure("Cannot decode as OttochainMessage", c.history)))
            }
          }
          .getOrElse(Left(DecodingFailure("Cannot decode as OttochainMessage: JSON is empty", Nil)))
      }
  }
}
