package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.domain.{FiberInput, ReservedKeys}

/**
 * Builds evaluation context for JsonLogic expressions.
 *
 * For state machines, context includes:
 * - state: current state data
 * - event: event payload
 * - eventType: event type string
 * - machineId: fiber UUID
 * - currentStateId: current state ID
 * - sequenceNumber: event sequence
 * - proofs: signer addresses
 * - parent: parent fiber data (if any)
 * - children: child fiber data
 * - machines: dependent machine states
 * - scriptOracles: dependent oracle states
 *
 * For oracles, context includes:
 * - _method: method name
 * - _args: method arguments
 * - _state: current oracle state
 */
trait ContextProvider[F[_]] {

  /**
   * Build full evaluation context for guard/effect expressions.
   * Includes proofs, dependencies, parent/child relationships.
   */
  def buildContext(
    fiber:        Records.FiberRecord,
    input:        FiberInput,
    proofs:       List[SignatureProof],
    dependencies: Set[UUID]
  ): F[JsonLogicValue]

  /**
   * Build simplified context for trigger/spawn expressions.
   * Includes fiber state, event, and parent/child relationships.
   * Does not include proofs or external dependencies.
   */
  def buildTriggerContext(
    fiber: Records.StateMachineFiberRecord,
    input: FiberInput
  ): F[JsonLogicValue]
}

object ContextProvider {

  /**
   * Create a ContextProvider with access to CalculatedState for dependency resolution.
   */
  def make[F[_]: Async: SecurityProvider](calculatedState: CalculatedState): ContextProvider[F] =
    new ContextProvider[F] {

      def buildContext(
        fiber:        Records.FiberRecord,
        input:        FiberInput,
        proofs:       List[SignatureProof],
        dependencies: Set[UUID]
      ): F[JsonLogicValue] = fiber match {
        case sm: Records.StateMachineFiberRecord =>
          input match {
            case FiberInput.Transition(eventType, payload) =>
              buildStateMachineContext(sm, eventType, payload, proofs, dependencies)
            case FiberInput.MethodCall(_, _, _, _) =>
              Async[F].raiseError(new RuntimeException("Cannot use MethodCall input with StateMachineFiberRecord"))
          }

        case oracle: Records.ScriptOracleFiberRecord =>
          input match {
            case FiberInput.MethodCall(method, args, _, _) =>
              buildOracleContext(oracle, method, args)
            case FiberInput.Transition(_, _) =>
              Async[F].raiseError(new RuntimeException("Cannot use Transition input with ScriptOracleFiberRecord"))
          }
      }

      // === State Machine Context ===

      private def buildStateMachineContext(
        fiber:        Records.StateMachineFiberRecord,
        eventType:    xyz.kd5ujc.schema.StateMachine.EventType,
        payload:      JsonLogicValue,
        proofs:       List[SignatureProof],
        dependencies: Set[UUID]
      ): F[JsonLogicValue] =
        for {
          proofsData   <- buildProofsContext(proofs)
          machinesData <- buildMachinesContext(dependencies)
          parentData   <- buildParentContext(fiber)
          childrenData <- buildChildrenContext(fiber)
          oraclesData  <- buildOraclesContext(dependencies)
        } yield MapValue(
          Map(
            ReservedKeys.STATE            -> fiber.stateData,
            ReservedKeys.EVENT            -> payload,
            ReservedKeys.EVENT_TYPE       -> StrValue(eventType.value),
            ReservedKeys.MACHINE_ID       -> StrValue(fiber.cid.toString),
            ReservedKeys.CURRENT_STATE_ID -> StrValue(fiber.currentState.value),
            ReservedKeys.SEQUENCE_NUMBER  -> IntValue(fiber.sequenceNumber),
            ReservedKeys.PROOFS           -> ArrayValue(proofsData),
            ReservedKeys.MACHINES         -> machinesData,
            ReservedKeys.PARENT           -> parentData,
            ReservedKeys.CHILDREN         -> childrenData,
            ReservedKeys.SCRIPT_ORACLES   -> oraclesData
          )
        )

      // === Oracle Context ===

      private def buildOracleContext(
        oracle: Records.ScriptOracleFiberRecord,
        method: String,
        args:   JsonLogicValue
      ): F[JsonLogicValue] =
        MapValue(
          Map(
            ReservedKeys.METHOD -> StrValue(method),
            ReservedKeys.ARGS   -> args,
            ReservedKeys.STATE  -> oracle.stateData.getOrElse(NullValue)
          )
        ).pure[F]

      // === Trigger Context (simplified, for spawns) ===

      def buildTriggerContext(
        fiber: Records.StateMachineFiberRecord,
        input: FiberInput
      ): F[JsonLogicValue] = {
        val (eventPayload, eventType) = input match {
          case FiberInput.Transition(et, payload)        => (payload, et.value)
          case FiberInput.MethodCall(method, args, _, _) => (args, method)
        }

        for {
          parentData   <- buildParentContext(fiber)
          childrenData <- buildChildrenContext(fiber)
        } yield MapValue(
          Map(
            ReservedKeys.STATE            -> fiber.stateData,
            ReservedKeys.EVENT            -> eventPayload,
            ReservedKeys.EVENT_TYPE       -> StrValue(eventType),
            ReservedKeys.MACHINE_ID       -> StrValue(fiber.cid.toString),
            ReservedKeys.CURRENT_STATE_ID -> StrValue(fiber.currentState.value),
            ReservedKeys.SEQUENCE_NUMBER  -> IntValue(fiber.sequenceNumber),
            ReservedKeys.PARENT           -> parentData,
            ReservedKeys.CHILDREN         -> childrenData
          )
        )
      }

      // === Shared Context Builders ===

      private def buildProofsContext(proofs: List[SignatureProof]): F[List[MapValue]] =
        proofs.traverse { case SignatureProof(id, sig) =>
          id.toAddress.map { address =>
            MapValue(
              Map(
                ReservedKeys.ADDRESS   -> StrValue(address.show),
                ReservedKeys.ID        -> StrValue(id.hex.value),
                ReservedKeys.SIGNATURE -> StrValue(sig.value.value)
              )
            )
          }
        }

      private def buildMachinesContext(dependencies: Set[UUID]): F[MapValue] =
        dependencies.toList
          .flatTraverse { machineId =>
            OptionT
              .fromOption[F](calculatedState.stateMachines.get(machineId))
              .map(fiber => List(machineId.toString -> buildFiberSummary(fiber)))
              .getOrElse(List.empty)
          }
          .map(_.toMap)
          .map(MapValue(_))

      private def buildParentContext(fiber: Records.StateMachineFiberRecord): F[JsonLogicValue] =
        OptionT
          .fromOption[F](fiber.parentFiberId)
          .flatMap(parentId => OptionT.fromOption[F](calculatedState.stateMachines.get(parentId)))
          .map(parentFiber => buildFiberSummary(parentFiber, includeId = true): JsonLogicValue)
          .getOrElse(NullValue: JsonLogicValue)

      private def buildChildrenContext(fiber: Records.StateMachineFiberRecord): F[MapValue] =
        fiber.childFiberIds.toList
          .flatTraverse { childId =>
            OptionT
              .fromOption[F](calculatedState.stateMachines.get(childId))
              .map(childFiber => List(childId.toString -> buildFiberSummary(childFiber)))
              .getOrElse(List.empty)
          }
          .map(_.toMap)
          .map(MapValue(_))

      private def buildOraclesContext(dependencies: Set[UUID]): F[MapValue] =
        dependencies.toList
          .flatTraverse { oracleId =>
            OptionT
              .fromOption[F](calculatedState.scriptOracles.get(oracleId))
              .map(oracle => List(oracleId.toString -> buildOracleSummary(oracle)))
              .getOrElse(List.empty)
          }
          .map(_.toMap)
          .map(MapValue(_))

      // === Summary Builders (reused across contexts) ===

      private def buildFiberSummary(
        fiber:     Records.StateMachineFiberRecord,
        includeId: Boolean = false
      ): MapValue = {
        val baseMap = Map(
          ReservedKeys.STATE            -> fiber.stateData,
          ReservedKeys.CURRENT_STATE_ID -> StrValue(fiber.currentState.value),
          ReservedKeys.SEQUENCE_NUMBER  -> IntValue(fiber.sequenceNumber)
        )
        val fullMap =
          if (includeId) baseMap + (ReservedKeys.MACHINE_ID -> StrValue(fiber.cid.toString))
          else baseMap
        MapValue(fullMap)
      }

      private def buildOracleSummary(oracle: Records.ScriptOracleFiberRecord): MapValue = {
        val invocationLogValues = oracle.invocationLog.map(buildInvocationSummary)
        MapValue(
          Map(
            ReservedKeys.STATE            -> oracle.stateData.getOrElse(NullValue),
            ReservedKeys.STATUS           -> StrValue(oracle.status.toString),
            ReservedKeys.INVOCATION_COUNT -> IntValue(oracle.invocationCount),
            ReservedKeys.INVOCATION_LOG   -> ArrayValue(invocationLogValues)
          )
        )
      }

      private def buildInvocationSummary(inv: Records.OracleInvocation): MapValue =
        MapValue(
          Map(
            ReservedKeys.METHOD     -> StrValue(inv.method),
            ReservedKeys.ARGS       -> inv.args,
            ReservedKeys.RESULT     -> inv.result,
            ReservedKeys.GAS_USED   -> IntValue(inv.gasUsed),
            ReservedKeys.INVOKED_AT -> IntValue(inv.invokedAt.value.value),
            ReservedKeys.INVOKED_BY -> StrValue(inv.invokedBy.show)
          )
        )
    }
}
