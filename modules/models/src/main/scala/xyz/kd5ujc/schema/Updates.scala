package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}

import xyz.kd5ujc.schema.fiber.{AccessControlPolicy, StateMachineDefinition}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax.EncoderOps

object Updates {

  sealed trait OttochainMessage extends DataUpdate {
    lazy val messageName: String = this.getClass.getSimpleName
    val fiberId: UUID
  }

  sealed trait StateMachineFiberOp

  @derive(decoder, encoder)
  final case class CreateStateMachine(
    fiberId:       UUID,
    definition:    StateMachineDefinition,
    initialData:   JsonLogicValue,
    parentFiberId: Option[UUID] = None
  ) extends StateMachineFiberOp
      with OttochainMessage

  /**
   * Event to trigger a state machine transition.
   *
   * @param fiberId Target fiber CID
   * @param eventName Type of event to trigger
   * @param payload Event payload data
   */
  @derive(decoder, encoder)
  final case class TransitionStateMachine(
    fiberId:   UUID,
    eventName: String,
    payload:   JsonLogicValue
  ) extends StateMachineFiberOp
      with OttochainMessage

  @derive(decoder, encoder)
  final case class ArchiveStateMachine(
    fiberId: UUID
  ) extends StateMachineFiberOp
      with OttochainMessage

  sealed trait ScriptOracleFiberOp

  @derive(decoder, encoder)
  final case class CreateScriptOracle(
    fiberId:       UUID,
    scriptProgram: JsonLogicExpression,
    initialState:  Option[JsonLogicValue],
    accessControl: AccessControlPolicy
  ) extends ScriptOracleFiberOp
      with OttochainMessage

  @derive(decoder, encoder)
  final case class InvokeScriptOracle(
    fiberId: UUID,
    method:  String,
    args:    JsonLogicValue
  ) extends ScriptOracleFiberOp
      with OttochainMessage

  object OttochainMessage {

    implicit val messageEncoder: Encoder[OttochainMessage] = {
      case u: Updates.CreateStateMachine     => Json.obj(u.messageName -> u.asJson)
      case u: Updates.TransitionStateMachine => Json.obj(u.messageName -> u.asJson)
      case u: Updates.ArchiveStateMachine    => Json.obj(u.messageName -> u.asJson)
      case u: Updates.CreateScriptOracle     => Json.obj(u.messageName -> u.asJson)
      case u: Updates.InvokeScriptOracle     => Json.obj(u.messageName -> u.asJson)
    }

    implicit val messageDecoder: Decoder[OttochainMessage] =
      (c: HCursor) => {
        val decoders = List(
          Decoder[Updates.CreateStateMachine],
          Decoder[Updates.TransitionStateMachine],
          Decoder[Updates.ArchiveStateMachine],
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
