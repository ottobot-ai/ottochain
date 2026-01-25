package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.Functor
import cats.effect.Async
import cats.mtl.Ask
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.metagraph_sdk.json_logic.core.{ArrayValue, StrValue}
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.{Records, StateMachine}
import xyz.kd5ujc.shared_data.fiber.domain.FiberContext

import eu.timepit.refined.refineV

/**
 * Processes spawn directives to create child fibers.
 *
 * Only applicable to state machines (oracles don't spawn children).
 *
 * For each SpawnDirective:
 * 1. Evaluate childIdExpr to get UUID
 * 2. Evaluate initialData expression
 * 3. Evaluate ownersExpr (or inherit from parent)
 * 4. Create new StateMachineFiberRecord
 */
trait SpawnProcessor[F[_]] {

  def processSpawns(
    directives:  List[StateMachine.SpawnDirective],
    parent:      Records.StateMachineFiberRecord,
    contextData: JsonLogicValue
  ): F[List[Records.StateMachineFiberRecord]]
}

object SpawnProcessor {

  /**
   * Create SpawnProcessor with explicit ordinal parameter.
   */
  def make[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    ordinal: SnapshotOrdinal
  ): SpawnProcessor[F] =
    new SpawnProcessor[F] {

      def processSpawns(
        directives:  List[StateMachine.SpawnDirective],
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): F[List[Records.StateMachineFiberRecord]] =
        directives.traverse(processSpawn(_, parent, contextData, ordinal))

      private def processSpawn(
        spawn:       StateMachine.SpawnDirective,
        parentFiber: Records.StateMachineFiberRecord,
        contextData: JsonLogicValue,
        ordinal:     SnapshotOrdinal
      ): F[Records.StateMachineFiberRecord] =
        for {
          // Evaluate childIdExpr to get the UUID
          childIdValue <- JsonLogicEvaluator
            .tailRecursive[F]
            .evaluate(spawn.childIdExpr, contextData, None)
            .flatMap(Async[F].fromEither)

          childIdStr <- childIdValue match {
            case StrValue(id) => id.pure[F]
            case _ =>
              Async[F].raiseError[String](
                new RuntimeException(s"childId must evaluate to string, got: $childIdValue")
              )
          }

          childId <- scala.util.Try(UUID.fromString(childIdStr)).toOption match {
            case Some(uuid) => uuid.pure[F]
            case None       => Async[F].raiseError[UUID](new RuntimeException(s"Invalid UUID format: $childIdStr"))
          }

          // Evaluate initialData expression
          initialData <- JsonLogicEvaluator
            .tailRecursive[F]
            .evaluate(spawn.initialData, contextData, None)
            .flatMap(Async[F].fromEither)

          // Evaluate owners expression or inherit from parent
          owners <- spawn.ownersExpr.fold(parentFiber.owners.pure[F]) { expr =>
            JsonLogicEvaluator
              .tailRecursive[F]
              .evaluate(expr, contextData, None)
              .flatMap(Async[F].fromEither)
              .flatMap {
                case ArrayValue(addresses) =>
                  addresses
                    .traverse[F, Address] {
                      case StrValue(addr) =>
                        refineV[DAGAddressRefined](addr) match {
                          case Right(refined) => (Address(refined): Address).pure[F]
                          case Left(err) =>
                            Async[F].raiseError[Address](new RuntimeException(s"Invalid owner address: $err"))
                        }
                      case _ => Async[F].raiseError[Address](new RuntimeException("Invalid owner address format"))
                    }
                    .map(_.toSet)
                case _ => Async[F].raiseError[Set[Address]](new RuntimeException("Owners expression must return array"))
              }
          }

          // Hash initial data
          initialDataHash <- initialData.computeDigest

          // Create child fiber record
          childFiber = Records.StateMachineFiberRecord(
            cid = childId,
            creationOrdinal = ordinal,
            previousUpdateOrdinal = ordinal,
            latestUpdateOrdinal = ordinal,
            definition = spawn.definition,
            currentState = spawn.definition.initialState,
            stateData = initialData,
            stateDataHash = initialDataHash,
            sequenceNumber = 0,
            owners = owners,
            status = Records.FiberStatus.Active,
            lastEventStatus = Records.EventProcessingStatus.Initialized,
            parentFiberId = Some(parentFiber.cid),
            childFiberIds = Set.empty
          )
        } yield childFiber
    }

  /**
   * Create SpawnProcessor that reads ordinal from FiberContext via Ask.
   * This is the MTL-aware version that works within FiberT.
   */
  def make[F[_]: Async: SecurityProvider: JsonLogicEvaluator, G[_]: Functor](implicit
    A: Ask[G, FiberContext]
  ): G[SpawnProcessor[F]] =
    ExecutionOps.askOrdinal[G].map { ordinal =>
      make[F](ordinal)
    }
}
