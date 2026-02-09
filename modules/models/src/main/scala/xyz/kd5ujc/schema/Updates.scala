package xyz.kd5ujc.schema

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{AccessControlPolicy, FiberOrdinal, StateMachineDefinition}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax.EncoderOps

object Updates {

  sealed trait OttochainMessage extends DataUpdate {
    lazy val messageName: String = this.getClass.getSimpleName
    val fiberId: UUID
  }

  /**
   * Mixin trait for operations that require sequence-based ordering.
   *
   * Operations that mutate fiber state must be processed in sequence order.
   * This trait enables a canonical ordering across all OttochainMessage types,
   * ensuring that sequenced operations are processed after creates and in
   * their correct sequence order within each fiber.
   */
  trait Sequenced {
    def fiberId: UUID
    def targetSequenceNumber: FiberOrdinal
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
    fiberId:              UUID,
    eventName:            String,
    payload:              JsonLogicValue,
    targetSequenceNumber: FiberOrdinal
  ) extends StateMachineFiberOp
      with OttochainMessage
      with Sequenced

  @derive(decoder, encoder)
  final case class ArchiveStateMachine(
    fiberId:              UUID,
    targetSequenceNumber: FiberOrdinal
  ) extends StateMachineFiberOp
      with OttochainMessage
      with Sequenced

  sealed trait ScriptFiberOp

  @derive(decoder, encoder)
  final case class CreateScript(
    fiberId:       UUID,
    scriptProgram: JsonLogicExpression,
    initialState:  Option[JsonLogicValue],
    accessControl: AccessControlPolicy
  ) extends ScriptFiberOp
      with OttochainMessage

  @derive(decoder, encoder)
  final case class InvokeScript(
    fiberId:              UUID,
    method:               String,
    args:                 JsonLogicValue,
    targetSequenceNumber: FiberOrdinal
  ) extends ScriptFiberOp
      with OttochainMessage
      with Sequenced

  object OttochainMessage {

    /**
     * Canonical ordering for OttochainMessage.
     *
     * Ordering rules:
     * 1. Non-sequenced messages (Creates) come first - they initialize fibers
     * 2. Sequenced messages are ordered by (fiberId, targetSequenceNumber)
     *
     * This ensures that within a batch of updates:
     * - Fiber creation happens before any transitions on that fiber
     * - Transitions for the same fiber are processed in sequence order
     * - Different fibers can interleave but each fiber's ops are sequential
     */
    implicit val ordering: Ordering[OttochainMessage] = Ordering.by {
      case s: Sequenced => (1, s.fiberId.toString, s.targetSequenceNumber.value.value)
      case m            => (0, m.fiberId.toString, 0L)
    }

    /**
     * Ordering for Signed[OttochainMessage] - delegates to message ordering.
     */
    implicit val signedOrdering: Ordering[Signed[OttochainMessage]] =
      Ordering.by(_.value)

    implicit val messageEncoder: Encoder[OttochainMessage] = {
      case u: Updates.CreateStateMachine     => Json.obj(u.messageName -> u.asJson)
      case u: Updates.TransitionStateMachine => Json.obj(u.messageName -> u.asJson)
      case u: Updates.ArchiveStateMachine    => Json.obj(u.messageName -> u.asJson)
      case u: Updates.CreateScript           => Json.obj(u.messageName -> u.asJson)
      case u: Updates.InvokeScript           => Json.obj(u.messageName -> u.asJson)
    }

    implicit val messageDecoder: Decoder[OttochainMessage] =
      (c: HCursor) => {
        val decoders = List(
          Decoder[Updates.CreateStateMachine],
          Decoder[Updates.TransitionStateMachine],
          Decoder[Updates.ArchiveStateMachine],
          Decoder[Updates.CreateScript],
          Decoder[Updates.InvokeScript]
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
