package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.Functor
import cats.data.Validated
import cats.effect.Async
import cats.mtl.Ask
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.{Records, StateMachine}
import xyz.kd5ujc.shared_data.fiber.domain.FiberContext

/**
 * Processes spawn directives to create child fibers.
 *
 * Only applicable to state machines (oracles don't spawn children).
 *
 * Processing flow:
 * 1. Validate all directives using SpawnValidator (collect all errors)
 * 2. If validation passes, create fiber records for each validated spawn
 * 3. Return clear error messages for validation failures
 */
trait SpawnProcessor[F[_]] {

  def processSpawns(
    directives:  List[StateMachine.SpawnDirective],
    parent:      Records.StateMachineFiberRecord,
    contextData: JsonLogicValue
  ): F[List[Records.StateMachineFiberRecord]]

  def processSpawnsValidated(
    directives:  List[StateMachine.SpawnDirective],
    parent:      Records.StateMachineFiberRecord,
    contextData: JsonLogicValue,
    knownFibers: Set[UUID]
  ): F[Either[SpawnProcessingError, List[Records.StateMachineFiberRecord]]]
}

/**
 * Error produced when spawn processing fails.
 */
final case class SpawnProcessingError(
  errors: List[SpawnValidationError]
) {

  def message: String = errors.map(_.message).mkString("; ")
}

object SpawnProcessor {

  /**
   * Create SpawnProcessor with explicit ordinal parameter.
   */
  def make[F[_]: Async: JsonLogicEvaluator](
    ordinal: SnapshotOrdinal
  ): SpawnProcessor[F] =
    new SpawnProcessor[F] {

      private val validator: SpawnValidator[F] = SpawnValidator.make[F]

      def processSpawns(
        directives:  List[StateMachine.SpawnDirective],
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): F[List[Records.StateMachineFiberRecord]] =
        processSpawnsValidated(directives, parent, contextData, Set.empty).flatMap {
          case Right(fibers) => fibers.pure[F]
          case Left(error) =>
            Async[F].raiseError[List[Records.StateMachineFiberRecord]](
              new RuntimeException(s"Spawn validation failed: ${error.message}")
            )
        }

      def processSpawnsValidated(
        directives:  List[StateMachine.SpawnDirective],
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue,
        knownFibers: Set[UUID]
      ): F[Either[SpawnProcessingError, List[Records.StateMachineFiberRecord]]] =
        directives.isEmpty
          .pure[F]
          .ifM(
            ifTrue = List.empty[Records.StateMachineFiberRecord].asRight[SpawnProcessingError].pure[F],
            ifFalse = validateAndProcess(directives, parent, contextData, knownFibers)
          )

      private def validateAndProcess(
        directives:  List[StateMachine.SpawnDirective],
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue,
        knownFibers: Set[UUID]
      ): F[Either[SpawnProcessingError, List[Records.StateMachineFiberRecord]]] =
        validator.validateSpawns(directives, parent, knownFibers, contextData).flatMap {
          case Validated.Valid(plan) =>
            createFibersFromPlan(plan, parent, contextData).map(_.asRight[SpawnProcessingError])

          case Validated.Invalid(errors) =>
            SpawnProcessingError(errors.toList).asLeft[List[Records.StateMachineFiberRecord]].pure[F]
        }

      private def createFibersFromPlan(
        plan:        SpawnPlan,
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): F[List[Records.StateMachineFiberRecord]] =
        plan.validatedSpawns.traverse { validatedSpawn =>
          createFiberRecord(validatedSpawn, parent, contextData)
        }

      private def createFiberRecord(
        spawn:       ValidatedSpawn,
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): F[Records.StateMachineFiberRecord] =
        for {
          // Evaluate initialData expression
          initialData <- JsonLogicEvaluator
            .tailRecursive[F]
            .evaluate(spawn.directive.initialData, contextData, None)
            .flatMap(Async[F].fromEither)

          // Hash initial data
          initialDataHash <- initialData.computeDigest

          // Create child fiber record
          childFiber = Records.StateMachineFiberRecord(
            cid = spawn.childId,
            creationOrdinal = ordinal,
            previousUpdateOrdinal = ordinal,
            latestUpdateOrdinal = ordinal,
            definition = spawn.directive.definition,
            currentState = spawn.directive.definition.initialState,
            stateData = initialData,
            stateDataHash = initialDataHash,
            sequenceNumber = 0,
            owners = spawn.resolvedOwners,
            status = Records.FiberStatus.Active,
            lastEventStatus = Records.EventProcessingStatus.Initialized,
            parentFiberId = Some(parent.cid),
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
