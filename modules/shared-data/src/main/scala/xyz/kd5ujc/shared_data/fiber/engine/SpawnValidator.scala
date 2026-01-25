package xyz.kd5ujc.shared_data.fiber.engine

import java.util.UUID

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.metagraph_sdk.json_logic.core.{ArrayValue, StrValue}
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}

import xyz.kd5ujc.schema.{Records, StateMachine}

import eu.timepit.refined.refineV

/**
 * Pre-validates spawn directives before execution.
 *
 * Collects all validation errors rather than failing on first error,
 * enabling better error messages and avoiding partial execution.
 */
trait SpawnValidator[F[_]] {

  def validateSpawns(
    directives:  List[StateMachine.SpawnDirective],
    parent:      Records.StateMachineFiberRecord,
    knownFibers: Set[UUID],
    contextData: JsonLogicValue
  ): F[ValidatedNel[SpawnValidationError, SpawnPlan]]
}

/**
 * Validation errors that can occur during spawn validation.
 */
sealed trait SpawnValidationError {
  def message: String
}

object SpawnValidationError {

  final case class InvalidChildIdFormat(expression: String, error: String) extends SpawnValidationError {
    def message: String = s"Child ID expression '$expression' did not return valid UUID: $error"
  }

  final case class DuplicateChildId(childId: UUID, count: Int) extends SpawnValidationError {
    def message: String = s"Duplicate child ID $childId appears $count times in spawn batch"
  }

  final case class ChildIdCollision(childId: UUID) extends SpawnValidationError {
    def message: String = s"Child ID $childId already exists as a fiber"
  }

  final case class InvalidOwnersExpression(error: String) extends SpawnValidationError {
    def message: String = s"Owners expression invalid: $error"
  }

  final case class InvalidOwnerAddress(address: String, error: String) extends SpawnValidationError {
    def message: String = s"Invalid owner address '$address': $error"
  }

  final case class ChildIdEvaluationFailed(error: String) extends SpawnValidationError {
    def message: String = s"Failed to evaluate child ID expression: $error"
  }

  final case class OwnersEvaluationFailed(error: String) extends SpawnValidationError {
    def message: String = s"Failed to evaluate owners expression: $error"
  }
}

/**
 * Validated spawn plan ready for execution.
 *
 * @param validatedSpawns List of directives with pre-resolved child IDs and owners
 */
final case class SpawnPlan(
  validatedSpawns: List[ValidatedSpawn]
)

/**
 * A spawn directive with pre-validated and resolved values.
 *
 * @param directive     The original spawn directive
 * @param childId       Pre-resolved child ID (validated as UUID)
 * @param resolvedOwners Pre-resolved owners (validated addresses)
 */
final case class ValidatedSpawn(
  directive:      StateMachine.SpawnDirective,
  childId:        UUID,
  resolvedOwners: Set[Address]
)

object SpawnValidator {

  def make[F[_]: Async]: SpawnValidator[F] =
    new SpawnValidator[F] {

      def validateSpawns(
        directives:  List[StateMachine.SpawnDirective],
        parent:      Records.StateMachineFiberRecord,
        knownFibers: Set[UUID],
        contextData: JsonLogicValue
      ): F[ValidatedNel[SpawnValidationError, SpawnPlan]] =
        directives
          .traverse(validateSingle(_, parent, contextData))
          .map {
            _.sequence.andThen {
              validateBatchConstraints(_, knownFibers)
            }
          }

      private def validateSingle(
        directive:   StateMachine.SpawnDirective,
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): F[ValidatedNel[SpawnValidationError, ValidatedSpawn]] =
        for {
          childIdResult <- evaluateChildId(directive, contextData)
          ownersResult  <- evaluateOwners(directive, parent, contextData)
        } yield (childIdResult, ownersResult).mapN { case (childId, owners) =>
          ValidatedSpawn(directive, childId, owners)
        }

      private def evaluateChildId(
        directive:   StateMachine.SpawnDirective,
        contextData: JsonLogicValue
      ): F[ValidatedNel[SpawnValidationError, UUID]] =
        JsonLogicEvaluator
          .tailRecursive[F]
          .evaluate(directive.childIdExpr, contextData, None)
          .map {
            case Right(StrValue(idStr)) =>
              scala.util.Try(UUID.fromString(idStr)).toOption match {
                case Some(uuid) => Validated.validNel(uuid)
                case None =>
                  Validated.invalidNel(
                    SpawnValidationError.InvalidChildIdFormat(idStr, "Not a valid UUID format")
                  )
              }
            case Right(other) =>
              Validated.invalidNel(
                SpawnValidationError.InvalidChildIdFormat(
                  other.toString.take(50),
                  s"Expected string, got ${other.getClass.getSimpleName}"
                )
              )
            case Left(err) =>
              Validated.invalidNel(
                SpawnValidationError.ChildIdEvaluationFailed(err.getMessage)
              )
          }

      private def evaluateOwners(
        directive:   StateMachine.SpawnDirective,
        parent:      Records.StateMachineFiberRecord,
        contextData: JsonLogicValue
      ): F[ValidatedNel[SpawnValidationError, Set[Address]]] =
        directive.ownersExpr match {
          case None =>
            Validated.validNel[SpawnValidationError, Set[Address]](parent.owners).pure[F]

          case Some(expr) =>
            JsonLogicEvaluator
              .tailRecursive[F]
              .evaluate(expr, contextData, None)
              .map {
                case Right(ArrayValue(addresses)) =>
                  addresses
                    .traverse[ValidatedNel[SpawnValidationError, *], Address] {
                      case StrValue(addr) =>
                        refineV[DAGAddressRefined](addr) match {
                          case Right(refined) => Validated.validNel(Address(refined))
                          case Left(err) =>
                            Validated.invalidNel(SpawnValidationError.InvalidOwnerAddress(addr, err))
                        }
                      case other =>
                        Validated.invalidNel(
                          SpawnValidationError.InvalidOwnerAddress(
                            other.toString.take(30),
                            "Expected string address"
                          )
                        )
                    }
                    .map(_.toSet)

                case Right(other) =>
                  Validated.invalidNel(
                    SpawnValidationError.InvalidOwnersExpression(
                      s"Expected array of addresses, got ${other.getClass.getSimpleName}"
                    )
                  )

                case Left(err) =>
                  Validated.invalidNel(
                    SpawnValidationError.OwnersEvaluationFailed(err.getMessage)
                  )
              }
        }

      private def validateBatchConstraints(
        spawns:      List[ValidatedSpawn],
        knownFibers: Set[UUID]
      ): ValidatedNel[SpawnValidationError, SpawnPlan] = {
        val childIds = spawns.map(_.childId)

        val duplicateErrors: List[SpawnValidationError] = childIds
          .groupBy(identity)
          .collect {
            case (id, occurrences) if occurrences.size > 1 =>
              SpawnValidationError.DuplicateChildId(id, occurrences.size)
          }
          .toList

        val collisionErrors: List[SpawnValidationError] = childIds
          .filter(knownFibers.contains)
          .distinct
          .map(SpawnValidationError.ChildIdCollision)

        val allErrors = duplicateErrors ++ collisionErrors

        NonEmptyList.fromList(allErrors) match {
          case Some(errors) => Validated.invalid(errors)
          case None         => Validated.validNel(SpawnPlan(spawns))
        }
      }
    }
}
