package xyz.kd5ujc.shared_data.lifecycle

import java.util.UUID

import cats.data.{EitherT, OptionT}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.{CalculatedState, Records, StateMachine}
import xyz.kd5ujc.shared_data.lifecycle.InputValidation.ValidationResult

import monocle.macros.GenLens

/**
 * Deterministic event processor for blockchain state machine execution.
 *
 * Key properties:
 * - No retries or error recovery
 * - Deterministic execution (same input always produces same output)
 * - Failed transactions consume gas but don't change state
 * - Input validation prevents malicious payloads
 * - Gas and depth limits prevent resource exhaustion
 */
object DeterministicEventProcessor {

  object ReservedKeys {
    // Effect Result Keys - Used in extracting side effects from transition results
    val TRIGGERS = "_triggers"
    val OUTPUTS = "_outputs"
    val SPAWN = "_spawn"
    val ORACLE_CALL = "_oracleCall"

    // Trigger Event Keys - Used in extractTriggerEvents for cross-machine event firing
    val TARGET_MACHINE_ID = "targetMachineId"
    val EVENT_TYPE = "eventType"
    val PAYLOAD = "payload"

    // Oracle Call Keys - Used in extractOracleCall for oracle invocation
    val CID = "cid"
    val METHOD = "method"
    val ARGS = "args"

    // Output Keys - Used in extractOutputs for external system outputs
    val OUTPUT_TYPE = "outputType"
    val DATA = "data"
    val DESTINATION = "destination"

    // Spawn Directive Keys - Used in extractSpawnDirectivesFromExpression for child machine creation
    val CHILD_ID = "childId"
    val DEFINITION = "definition"
    val INITIAL_DATA = "initialData"
    val OWNERS = "owners"

    // State Machine Definition Keys - Used in parseStateMachineDefinition(FromExpression)
    val STATES = "states"
    val INITIAL_STATE = "initialState"
    val TRANSITIONS = "transitions"
    val METADATA = "metadata"
    val IS_FINAL = "isFinal"
    val VALUE = "value"

    // Transition Keys - Used in parseTransitions(FromExpression)
    val FROM = "from"
    val TO = "to"
    val GUARD = "guard"
    val EFFECT = "effect"
    val DEPENDENCIES = "dependencies"

    // JsonLogic Expression Keys - Used in valueToExpression
    val VAR = "var"

    // Context Data Keys - Used in buildContextData and related methods
    val STATE = "state"
    val EVENT = "event"
    val MACHINE_ID = "machineId"
    val CURRENT_STATE_ID = "currentStateId"
    val SEQUENCE_NUMBER = "sequenceNumber"
    val PROOFS = "proofs"
    val ADDRESS = "address"
    val ID = "id"
    val SIGNATURE = "signature"
    val MACHINES = "machines"
    val PARENT = "parent"
    val CHILDREN = "children"

    def isInternal(key: String): Boolean = key.startsWith("_")
  }

  private val calculatedStateRecordsLens = GenLens[CalculatedState](_.stateMachines)

  def processEvent[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    fiber:            Records.StateMachineFiberRecord,
    event:            StateMachine.Event,
    proofs:           List[SignatureProof],
    ordinal:          SnapshotOrdinal,
    calculatedState:  CalculatedState,
    executionContext: StateMachine.ExecutionContext,
    gasLimit:         Long
  ): F[StateMachine.ProcessingResult] = {
    val validationResult = InputValidation.validateTransaction(fiber.cid, event, gasLimit)
    val eventKey = (fiber.cid, event.eventType)

    validationResult.isValid
      .pure[F]
      .ifM(
        ifTrue = (executionContext.depth >= executionContext.maxDepth)
          .pure[F]
          .ifM(
            ifTrue = StateMachine.DepthExceeded(executionContext.depth).pure[F].widen,
            ifFalse = (executionContext.gasUsed >= gasLimit)
              .pure[F]
              .ifM(
                ifTrue = StateMachine.GasExhausted(executionContext.gasUsed.toInt).pure[F].widen,
                ifFalse = executionContext.processedEvents
                  .contains(eventKey)
                  .pure[F]
                  .ifM(
                    ifTrue = StateMachine
                      .Failure(
                        StateMachine.FailureReason.CycleDetected(fiber.cid, event.eventType)
                      )
                      .pure[F]
                      .widen,
                    ifFalse =
                      processTransition(fiber, event, proofs, ordinal, calculatedState, executionContext, gasLimit)
                  )
              )
          ),
        ifFalse = StateMachine
          .Failure(
            StateMachine.FailureReason.ValidationFailed(
              validationResult.errors.map(e => s"${e.field}: ${e.message}").mkString(", "),
              ordinal
            )
          )
          .pure[F]
          .widen
      )
  }

  private def processTransition[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    fiber:            Records.StateMachineFiberRecord,
    event:            StateMachine.Event,
    proofs:           List[SignatureProof],
    ordinal:          SnapshotOrdinal,
    calculatedState:  CalculatedState,
    executionContext: StateMachine.ExecutionContext,
    gasLimit:         Long
  ): F[StateMachine.ProcessingResult] = {
    val updatedContext = executionContext.copy(
      processedEvents = executionContext.processedEvents + ((fiber.cid, event.eventType))
    )

    fiber.definition.transitionMap
      .get((fiber.currentState, event.eventType))
      .fold[F[StateMachine.ProcessingResult]](
        (StateMachine.Failure(
          StateMachine.FailureReason.NoTransitionFound(fiber.currentState, event.eventType)
        ): StateMachine.ProcessingResult).pure[F]
      )(transitions =>
        tryTransitions(fiber, event, proofs, ordinal, calculatedState, updatedContext, transitions, gasLimit)
      )
  }

  private def tryTransitions[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    fiber:            Records.StateMachineFiberRecord,
    event:            StateMachine.Event,
    proofs:           List[SignatureProof],
    ordinal:          SnapshotOrdinal,
    calculatedState:  CalculatedState,
    executionContext: StateMachine.ExecutionContext,
    transitions:      List[StateMachine.Transition],
    gasLimit:         Long,
    attemptedGuards:  Int = 0
  ): F[StateMachine.ProcessingResult] =
    transitions match {
      case Nil =>
        StateMachine
          .Failure(
            StateMachine.FailureReason.NoGuardMatched(fiber.currentState, event.eventType, attemptedGuards)
          )
          .pure[F]
          .widen

      case transition :: rest =>
        InputValidation.validateExpression(transition.guard) match {
          case ValidationResult(false, errors) =>
            StateMachine
              .Failure(
                StateMachine.FailureReason.GuardEvaluationError(
                  s"Invalid guard: ${errors.map(_.message).mkString(", ")}"
                )
              )
              .pure[F]
              .widen

          case _ =>
            val gasForGuard = 10
            val newGasUsed = executionContext.gasUsed + gasForGuard

            (newGasUsed > gasLimit)
              .pure[F]
              .ifM(
                ifTrue = StateMachine.GasExhausted(newGasUsed.toInt).pure[F].widen,
                ifFalse = for {
                  contextData <- buildContextData(fiber, event, proofs, calculatedState, transition.dependencies)
                  result <- evaluateGuardAndApply(
                    fiber,
                    transition,
                    event,
                    contextData,
                    executionContext.copy(gasUsed = newGasUsed),
                    gasLimit,
                    ordinal,
                    proofs,
                    calculatedState,
                    rest,
                    attemptedGuards
                  )
                } yield result
              )
        }
    }

  private def evaluateGuardAndApply[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    fiber:            Records.StateMachineFiberRecord,
    transition:       StateMachine.Transition,
    event:            StateMachine.Event,
    contextData:      JsonLogicValue,
    executionContext: StateMachine.ExecutionContext,
    gasLimit:         Long,
    ordinal:          SnapshotOrdinal,
    proofs:           List[SignatureProof],
    calculatedState:  CalculatedState,
    rest:             List[StateMachine.Transition],
    attemptedGuards:  Int
  ): F[StateMachine.ProcessingResult] =
    JsonLogicEvaluator
      .tailRecursive[F]
      .evaluate(transition.guard, contextData, None)
      .flatMap[StateMachine.ProcessingResult] {
        case Right(BoolValue(true)) =>
          applyTransition(fiber, transition, ordinal, event, contextData, executionContext, gasLimit)

        case Right(BoolValue(false)) =>
          tryTransitions(
            fiber,
            event,
            proofs,
            ordinal,
            calculatedState,
            executionContext,
            rest,
            gasLimit,
            attemptedGuards + 1
          )

        case Right(other) =>
          (StateMachine.Failure(
            StateMachine.FailureReason.GuardEvaluationError(
              s"Guard returned non-boolean value: ${other.getClass.getSimpleName}"
            )
          ): StateMachine.ProcessingResult).pure[F]

        case Left(err) =>
          (StateMachine.Failure(
            StateMachine.FailureReason.EffectEvaluationError(err.getMessage)
          ): StateMachine.ProcessingResult).pure[F]
      }

  private def applyTransition[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    fiber:            Records.StateMachineFiberRecord,
    transition:       StateMachine.Transition,
    ordinal:          SnapshotOrdinal,
    event:            StateMachine.Event,
    contextData:      JsonLogicValue,
    executionContext: StateMachine.ExecutionContext,
    gasLimit:         Long
  ): F[StateMachine.ProcessingResult] =
    InputValidation.validateExpression(transition.effect) match {
      case ValidationResult(false, errors) =>
        StateMachine
          .Failure(
            StateMachine.FailureReason.EffectEvaluationError(
              s"Invalid effect: ${errors.map(_.message).mkString(", ")}"
            )
          )
          .pure[F]
          .widen

      case _ =>
        val gasForEffect = 20L
        val newGasUsed = executionContext.gasUsed + gasForEffect

        (newGasUsed > gasLimit)
          .pure[F]
          .ifM(
            ifTrue = StateMachine.GasExhausted(newGasUsed.toInt).pure[F].widen,
            ifFalse = executeEffect(fiber, transition, ordinal, event, contextData, newGasUsed, gasLimit)
          )
    }

  private def executeEffect[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    fiber:       Records.StateMachineFiberRecord,
    transition:  StateMachine.Transition,
    ordinal:     SnapshotOrdinal,
    event:       StateMachine.Event,
    contextData: JsonLogicValue,
    gasUsed:     Long,
    gasLimit:    Long
  ): F[StateMachine.ProcessingResult] =
    (for {
      currentMap <- EitherT.fromEither[F](
        fiber.stateData match {
          case m: MapValue => m.asRight[StateMachine.FailureReason]
          case _           => StateMachine.FailureReason.EffectEvaluationError("State data must be MapValue").asLeft
        }
      )

      effectResult <- EitherT[F, StateMachine.FailureReason, JsonLogicValue](
        JsonLogicEvaluator
          .tailRecursive[F]
          .evaluate(transition.effect, contextData, None)
          .map(_.leftMap(err => StateMachine.FailureReason.EffectEvaluationError(err.getMessage)))
      )

      _ <- EitherT.fromEither[F](
        InputValidation.validateStateSize(effectResult) match {
          case ValidationResult(false, errors) =>
            StateMachine.FailureReason
              .EffectEvaluationError(s"Effect result too large: ${errors.mkString(", ")}")
              .asLeft
          case _ => ().asRight
        }
      )

      outputs = extractOutputs(effectResult)
      spawnMachines = extractSpawnDirectivesFromExpression(transition.effect)

      triggerEvents <- EitherT.liftF[F, StateMachine.FailureReason, List[StateMachine.TriggerEvent]](
        extractTriggerEvents(effectResult, contextData)
      )

      oracleCallTrigger <- EitherT.liftF[F, StateMachine.FailureReason, Option[StateMachine.TriggerEvent]](
        extractOracleCall(effectResult, contextData)
      )

      allTriggers = triggerEvents ++ oracleCallTrigger.toList

      gasForTriggers = allTriggers.size * 5L
      gasForSpawns = spawnMachines.size * 50L
      finalGasUsed = gasUsed + gasForTriggers + gasForSpawns

      result <- EitherT(
        (finalGasUsed > gasLimit)
          .pure[F]
          .ifM(
            ifTrue = (StateMachine.GasExhausted(finalGasUsed.toInt): StateMachine.ProcessingResult)
              .asRight[StateMachine.FailureReason]
              .pure[F],
            ifFalse = mergeEffectIntoState(currentMap, effectResult).map { mergeResult =>
              mergeResult.map { newStateData =>
                val receipt = Records.EventReceipt(
                  sequenceNumber = fiber.sequenceNumber + 1,
                  eventType = event.eventType,
                  ordinal = ordinal,
                  fromState = fiber.currentState,
                  toState = transition.to,
                  success = true,
                  gasUsed = finalGasUsed,
                  triggersFired = allTriggers.size,
                  outputs = outputs,
                  errorMessage = None
                )
                StateMachine.StateMachineSuccess(
                  newState = transition.to,
                  newStateData = newStateData,
                  triggerEvents = allTriggers,
                  spawnMachines = spawnMachines,
                  receipt = receipt.some,
                  outputs = outputs
                ): StateMachine.ProcessingResult
              }
            }
          )
      )
    } yield result).valueOr { reason =>
      StateMachine.Failure(reason): StateMachine.ProcessingResult
    }

  private def buildContextData[F[_]: Async: SecurityProvider](
    fiber:           Records.StateMachineFiberRecord,
    event:           StateMachine.Event,
    proofs:          List[SignatureProof],
    calculatedState: CalculatedState,
    dependencies:    Set[UUID]
  ): F[JsonLogicValue] =
    for {
      proofsData <- proofs.traverse { case SignatureProof(id, sig) =>
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

      machinesData <- buildMachinesContext(dependencies, calculatedState)
      parentData   <- buildParentContext(fiber, calculatedState)
      childrenData <- buildChildrenContext(fiber, calculatedState)
      oraclesData  <- buildOraclesContext(dependencies, calculatedState)
    } yield MapValue(
      Map(
        ReservedKeys.STATE            -> fiber.stateData,
        ReservedKeys.EVENT            -> event.payload,
        ReservedKeys.EVENT_TYPE       -> StrValue(event.eventType.value),
        ReservedKeys.MACHINE_ID       -> StrValue(fiber.cid.toString),
        ReservedKeys.CURRENT_STATE_ID -> StrValue(fiber.currentState.value),
        ReservedKeys.SEQUENCE_NUMBER  -> IntValue(fiber.sequenceNumber),
        ReservedKeys.PROOFS           -> ArrayValue(proofsData),
        ReservedKeys.MACHINES         -> machinesData,
        ReservedKeys.PARENT           -> parentData,
        ReservedKeys.CHILDREN         -> childrenData,
        "scriptOracles"               -> oraclesData
      )
    )

  private def buildMachinesContext[F[_]: Async](
    dependencies:    Set[UUID],
    calculatedState: CalculatedState
  ): F[MapValue] =
    dependencies.toList
      .flatTraverse { machineId =>
        OptionT
          .fromOption[F](
            calculatedState.records
              .get(machineId)
              .collect { case r: Records.StateMachineFiberRecord => r }
          )
          .map { otherFiber =>
            List(
              machineId.toString -> MapValue(
                Map(
                  ReservedKeys.STATE            -> otherFiber.stateData,
                  ReservedKeys.CURRENT_STATE_ID -> StrValue(otherFiber.currentState.value),
                  ReservedKeys.SEQUENCE_NUMBER  -> IntValue(otherFiber.sequenceNumber)
                )
              )
            )
          }
          .getOrElse(List.empty)
      }
      .map(_.toMap)
      .map(MapValue(_))

  private def buildParentContext[F[_]: Async](
    fiber:           Records.StateMachineFiberRecord,
    calculatedState: CalculatedState
  ): F[JsonLogicValue] =
    OptionT
      .fromOption[F](fiber.parentFiberId)
      .flatMap { parentId =>
        OptionT.fromOption[F](
          calculatedState.records
            .get(parentId)
            .collect { case r: Records.StateMachineFiberRecord => r }
        )
      }
      .map { parentFiber =>
        MapValue(
          Map(
            ReservedKeys.STATE            -> parentFiber.stateData,
            ReservedKeys.CURRENT_STATE_ID -> StrValue(parentFiber.currentState.value),
            ReservedKeys.SEQUENCE_NUMBER  -> IntValue(parentFiber.sequenceNumber),
            ReservedKeys.MACHINE_ID       -> StrValue(parentFiber.cid.toString)
          )
        ): JsonLogicValue
      }
      .getOrElse(NullValue: JsonLogicValue)

  private def buildChildrenContext[F[_]: Async](
    fiber:           Records.StateMachineFiberRecord,
    calculatedState: CalculatedState
  ): F[MapValue] =
    fiber.childFiberIds.toList
      .flatTraverse { childId =>
        OptionT
          .fromOption[F](
            calculatedState.records
              .get(childId)
              .collect { case r: Records.StateMachineFiberRecord => r }
          )
          .map { childFiber =>
            List(
              childId.toString -> MapValue(
                Map(
                  ReservedKeys.STATE            -> childFiber.stateData,
                  ReservedKeys.CURRENT_STATE_ID -> StrValue(childFiber.currentState.value),
                  ReservedKeys.SEQUENCE_NUMBER  -> IntValue(childFiber.sequenceNumber)
                )
              )
            )
          }
          .getOrElse(List.empty)
      }
      .map(_.toMap)
      .map(MapValue(_))

  private def buildOraclesContext[F[_]: Async](
    dependencies:    Set[UUID],
    calculatedState: CalculatedState
  ): F[MapValue] =
    dependencies.toList
      .flatTraverse { oracleId =>
        OptionT
          .fromOption[F](
            calculatedState.scriptOracles.get(oracleId)
          )
          .map { oracle =>
            val invocationLogValues = oracle.invocationLog.map { inv =>
              MapValue(
                Map(
                  "method"    -> StrValue(inv.method),
                  "args"      -> inv.args,
                  "result"    -> inv.result,
                  "gasUsed"   -> IntValue(inv.gasUsed),
                  "invokedAt" -> IntValue(inv.invokedAt.value.value),
                  "invokedBy" -> StrValue(inv.invokedBy.show)
                )
              )
            }

            List(
              oracleId.toString -> MapValue(
                Map(
                  "state"           -> oracle.stateData.getOrElse(NullValue),
                  "stateData"       -> oracle.stateData.getOrElse(NullValue),
                  "status"          -> StrValue(oracle.status.toString),
                  "invocationCount" -> IntValue(oracle.invocationCount),
                  "invocationLog"   -> ArrayValue(invocationLogValues)
                )
              )
            )
          }
          .getOrElse(List.empty)
      }
      .map(_.toMap)
      .map(MapValue(_))

  private def mergeEffectIntoState[F[_]: Async](
    currentState: MapValue,
    effectResult: JsonLogicValue
  ): F[Either[StateMachine.FailureReason, MapValue]] =
    effectResult match {
      case m: MapValue =>
        val filteredMap = m.value.filterNot { case (key, _) => ReservedKeys.isInternal(key) }
        MapValue(currentState.value ++ filteredMap).asRight[StateMachine.FailureReason].pure[F]

      case ArrayValue(updates) =>
        updates
          .foldLeftM[F, Either[StateMachine.FailureReason, Map[String, JsonLogicValue]]](currentState.value.asRight) {
            case (Right(acc), ArrayValue(List(StrValue(key), value))) if !ReservedKeys.isInternal(key) =>
              (acc + (key -> value)).asRight[StateMachine.FailureReason].pure[F]
            case (Right(acc), ArrayValue(List(StrValue(key), _))) if ReservedKeys.isInternal(key) =>
              acc.asRight[StateMachine.FailureReason].pure[F]
            case (Left(err), _) =>
              err.asLeft[Map[String, JsonLogicValue]].pure[F]
            case (_, other) =>
              (StateMachine.FailureReason.EffectEvaluationError(
                s"Invalid effect format: $other"
              ): StateMachine.FailureReason)
                .asLeft[Map[String, JsonLogicValue]]
                .pure[F]
          }
          .map(_.map(MapValue(_)))

      case _ =>
        (StateMachine.FailureReason.EffectEvaluationError(
          s"Effect must return MapValue or ArrayValue, got: $effectResult"
        ): StateMachine.FailureReason)
          .asLeft[MapValue]
          .pure[F]
    }

  private def extractTriggerEvents[F[_]: Async: JsonLogicEvaluator](
    effectResult: JsonLogicValue,
    contextData:  JsonLogicValue
  ): F[List[StateMachine.TriggerEvent]] =
    effectResult match {
      case MapValue(map) =>
        map
          .get(ReservedKeys.TRIGGERS)
          .collect { case ArrayValue(triggers) =>
            triggers
              .traverse {
                case MapValue(triggerMap) =>
                  (for {
                    targetIdStr <- OptionT.fromOption[F](triggerMap.get(ReservedKeys.TARGET_MACHINE_ID).collect {
                      case StrValue(id) => id
                    })
                    targetId <- OptionT.fromOption[F](scala.util.Try(UUID.fromString(targetIdStr)).toOption)
                    eventType <- OptionT.fromOption[F](
                      triggerMap.get(ReservedKeys.EVENT_TYPE).collect { case StrValue(et) =>
                        StateMachine.EventType(et)
                      }
                    )
                    payloadValue <- OptionT.fromOption[F](triggerMap.get(ReservedKeys.PAYLOAD))
                    payloadExpr = valueToExpression(payloadValue)
                    evaluatedPayload <- OptionT(
                      JsonLogicEvaluator.tailRecursive[F].evaluate(payloadExpr, contextData, None).map(_.toOption)
                    )
                  } yield StateMachine.TriggerEvent(
                    targetMachineId = targetId,
                    eventType = eventType,
                    payloadExpr = ConstExpression(evaluatedPayload)
                  )).value
                case _ => none[StateMachine.TriggerEvent].pure[F]
              }
              .map(_.flatten)
          }
          .getOrElse(List.empty.pure[F])

      case ArrayValue(updates) =>
        updates
          .collectFirst { case ArrayValue(List(StrValue(ReservedKeys.TRIGGERS), ArrayValue(triggers))) =>
            triggers
              .traverse {
                case MapValue(triggerMap) =>
                  (for {
                    targetId <- OptionT.fromOption[F](
                      triggerMap.get(ReservedKeys.TARGET_MACHINE_ID).collect { case StrValue(id) =>
                        UUID.fromString(id)
                      }
                    )
                    eventType <- OptionT.fromOption[F](
                      triggerMap.get(ReservedKeys.EVENT_TYPE).collect { case StrValue(et) =>
                        StateMachine.EventType(et)
                      }
                    )
                    payloadValue <- OptionT.fromOption[F](triggerMap.get(ReservedKeys.PAYLOAD))
                    payloadExpr = valueToExpression(payloadValue)
                    evaluatedPayload <- OptionT(
                      JsonLogicEvaluator.tailRecursive[F].evaluate(payloadExpr, contextData, None).map(_.toOption)
                    )
                  } yield StateMachine.TriggerEvent(
                    targetMachineId = targetId,
                    eventType = eventType,
                    payloadExpr = ConstExpression(evaluatedPayload)
                  )).value
                case _ => none[StateMachine.TriggerEvent].pure[F]
              }
              .map(_.flatten)
          }
          .getOrElse(List.empty.pure[F])

      case _ => List.empty.pure[F]
    }

  private def extractOracleCall[F[_]: Async: JsonLogicEvaluator](
    effectResult: JsonLogicValue,
    contextData:  JsonLogicValue
  ): F[Option[StateMachine.TriggerEvent]] =
    effectResult match {
      case MapValue(map) =>
        map.get(ReservedKeys.ORACLE_CALL) match {
          case Some(MapValue(oracleCallMap)) =>
            (for {
              cidStr   <- OptionT.fromOption[F](oracleCallMap.get(ReservedKeys.CID).collect { case StrValue(id) => id })
              targetId <- OptionT.fromOption[F](scala.util.Try(UUID.fromString(cidStr)).toOption)
              method <- OptionT.fromOption[F](oracleCallMap.get(ReservedKeys.METHOD).collect { case StrValue(m) => m })
              argsValue <- OptionT.fromOption[F](oracleCallMap.get(ReservedKeys.ARGS))
              argsExpr = valueToExpression(argsValue)
              evaluatedArgs <- OptionT(
                JsonLogicEvaluator.tailRecursive[F].evaluate(argsExpr, contextData, None).map(_.toOption)
              )
            } yield StateMachine.TriggerEvent(
              targetMachineId = targetId,
              eventType = StateMachine.EventType(method),
              payloadExpr = ConstExpression(evaluatedArgs)
            )).value
          case _ => none[StateMachine.TriggerEvent].pure[F]
        }

      case _ => none[StateMachine.TriggerEvent].pure[F]
    }

  private def extractOutputs(effectResult: JsonLogicValue): List[Records.StructuredOutput] =
    effectResult match {
      case MapValue(map) =>
        map
          .get(ReservedKeys.OUTPUTS)
          .collect { case ArrayValue(outputs) =>
            outputs.flatMap {
              case MapValue(outputMap) =>
                (for {
                  outputType <- outputMap.get(ReservedKeys.OUTPUT_TYPE).collect { case StrValue(t) => t }
                  data       <- outputMap.get(ReservedKeys.DATA)
                  destination = outputMap.get(ReservedKeys.DESTINATION).collect { case StrValue(d) => d }
                } yield Records.StructuredOutput(outputType, data, destination)).toList
              case _ => List.empty
            }
          }
          .getOrElse(List.empty)

      case ArrayValue(updates) =>
        updates
          .collectFirst { case ArrayValue(List(StrValue(ReservedKeys.OUTPUTS), ArrayValue(outputs))) =>
            outputs.flatMap {
              case MapValue(outputMap) =>
                (for {
                  outputType <- outputMap.get(ReservedKeys.OUTPUT_TYPE).collect { case StrValue(t) => t }
                  data       <- outputMap.get(ReservedKeys.DATA)
                  destination = outputMap.get(ReservedKeys.DESTINATION).collect { case StrValue(d) => d }
                } yield Records.StructuredOutput(outputType, data, destination)).toList
              case _ => List.empty
            }
          }
          .getOrElse(List.empty)

      case _ => List.empty
    }

  private def parseStateMachineDefinitionFromExpression(
    defExpr: JsonLogicExpression
  ): Option[StateMachine.StateMachineDefinition] =
    defExpr match {
      case MapExpression(defMap) =>
        (for {
          statesExpr       <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.STATES))
          states           <- OptionT.fromOption[cats.Id](parseStatesFromExpression(statesExpr))
          initialStateExpr <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.INITIAL_STATE))
          initialState     <- OptionT.fromOption[cats.Id](parseInitialState(initialStateExpr))
          transitionsExpr  <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.TRANSITIONS))
          transitions      <- OptionT.fromOption[cats.Id](parseTransitionsFromExpression(transitionsExpr))
        } yield {
          val metadata = defMap.get(ReservedKeys.METADATA).flatMap {
            case ConstExpression(v) => v.some
            case _                  => none
          }
          StateMachine.StateMachineDefinition(states, initialState, transitions, metadata)
        }).value

      case ConstExpression(v) =>
        parseStateMachineDefinition(v)

      case _ => none
    }

  private def parseInitialState(expr: JsonLogicExpression): Option[StateMachine.StateId] =
    expr match {
      case ConstExpression(StrValue(s)) => StateMachine.StateId(s).some
      case MapExpression(m) =>
        m.get(ReservedKeys.VALUE).flatMap {
          case ConstExpression(StrValue(s)) => StateMachine.StateId(s).some
          case _                            => none
        }
      case _ => none
    }

  private def parseStatesFromExpression(
    statesExpr: JsonLogicExpression
  ): Option[Map[StateMachine.StateId, StateMachine.State]] =
    statesExpr match {
      case MapExpression(statesMap) =>
        statesMap.toList
          .traverse { case (stateId, stateExpr) =>
            stateExpr match {
              case MapExpression(stateMap) =>
                val isFinal = stateMap
                  .get(ReservedKeys.IS_FINAL)
                  .flatMap {
                    case ConstExpression(BoolValue(b)) => b.some
                    case _                             => none
                  }
                  .getOrElse(false)
                val metadata = stateMap.get(ReservedKeys.METADATA).flatMap {
                  case ConstExpression(v) => v.some
                  case _                  => none
                }
                (StateMachine.StateId(stateId) -> StateMachine.State(
                  id = StateMachine.StateId(stateId),
                  isFinal = isFinal,
                  metadata = metadata
                )).some
              case _ => none
            }
          }
          .map(_.toMap)
      case _ => none
    }

  private def parseTransitionsFromExpression(
    transitionsExpr: JsonLogicExpression
  ): Option[List[StateMachine.Transition]] =
    transitionsExpr match {
      case ArrayExpression(transitionsList) =>
        transitionsList.traverse {
          case MapExpression(transMap) =>
            (for {
              fromExpr      <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.FROM))
              from          <- OptionT.fromOption[cats.Id](parseStateId(fromExpr))
              toExpr        <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.TO))
              to            <- OptionT.fromOption[cats.Id](parseStateId(toExpr))
              eventTypeExpr <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EVENT_TYPE))
              eventType     <- OptionT.fromOption[cats.Id](parseEventType(eventTypeExpr))
              guard         <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.GUARD))
              effect        <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EFFECT))
            } yield {
              val dependencies = transMap
                .get(ReservedKeys.DEPENDENCIES)
                .flatMap {
                  case ArrayExpression(deps) =>
                    deps
                      .flatMap {
                        case ConstExpression(StrValue(id)) => scala.util.Try(UUID.fromString(id)).toOption
                        case _                             => none
                      }
                      .toSet
                      .some
                  case _ => none
                }
                .getOrElse(Set.empty)

              StateMachine.Transition(from, to, eventType, guard, effect, dependencies)
            }).value
          case _ => none
        }
      case _ => none
    }

  private def parseStateId(expr: JsonLogicExpression): Option[StateMachine.StateId] =
    expr match {
      case ConstExpression(StrValue(s)) => StateMachine.StateId(s).some
      case MapExpression(m) =>
        m.get(ReservedKeys.VALUE).flatMap {
          case ConstExpression(StrValue(s)) => StateMachine.StateId(s).some
          case _                            => none
        }
      case _ => none
    }

  private def parseEventType(expr: JsonLogicExpression): Option[StateMachine.EventType] =
    expr match {
      case ConstExpression(StrValue(et)) => StateMachine.EventType(et).some
      case MapExpression(m) =>
        m.get(ReservedKeys.VALUE).flatMap {
          case ConstExpression(StrValue(et)) => StateMachine.EventType(et).some
          case _                             => none
        }
      case _ => none
    }

  private def parseStateMachineDefinition(defValue: JsonLogicValue): Option[StateMachine.StateMachineDefinition] =
    defValue match {
      case MapValue(defMap) =>
        (for {
          statesValue       <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.STATES))
          states            <- OptionT.fromOption[cats.Id](parseStates(statesValue))
          initialStateValue <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.INITIAL_STATE))
          initialState      <- OptionT.fromOption[cats.Id](parseInitialStateValue(initialStateValue))
          transitionsValue  <- OptionT.fromOption[cats.Id](defMap.get(ReservedKeys.TRANSITIONS))
          transitions       <- OptionT.fromOption[cats.Id](parseTransitions(transitionsValue))
        } yield StateMachine.StateMachineDefinition(
          states = states,
          initialState = initialState,
          transitions = transitions,
          metadata = defMap.get(ReservedKeys.METADATA)
        )).value
      case _ => none
    }

  private def parseInitialStateValue(value: JsonLogicValue): Option[StateMachine.StateId] =
    value match {
      case StrValue(s) => StateMachine.StateId(s).some
      case MapValue(m) => m.get(ReservedKeys.VALUE).collect { case StrValue(s) => StateMachine.StateId(s) }
      case _           => none
    }

  private def valueToExpression(value: JsonLogicValue): JsonLogicExpression = value match {
    case MapValue(m) if m.size == 1 && m.contains(ReservedKeys.VAR) =>
      m.get(ReservedKeys.VAR) match {
        case Some(StrValue(path))                               => VarExpression(Left(path), none)
        case Some(ArrayValue(List(StrValue(path), defaultVal))) => VarExpression(Left(path), defaultVal.some)
        case Some(ArrayValue(List(StrValue(path))))             => VarExpression(Left(path), none)
        case Some(other)                                        => VarExpression(Right(valueToExpression(other)), none)
        case None                                               => ConstExpression(value)
      }
    case MapValue(m)   => MapExpression(m.view.mapValues(valueToExpression).toMap)
    case ArrayValue(a) => ArrayExpression(a.map(valueToExpression))
    case other         => ConstExpression(other)
  }

  private def parseStates(statesValue: JsonLogicValue): Option[Map[StateMachine.StateId, StateMachine.State]] =
    statesValue match {
      case MapValue(statesMap) =>
        statesMap.toList
          .traverse { case (stateId, stateValue) =>
            stateValue match {
              case MapValue(stateMap) =>
                val isFinal = stateMap
                  .get(ReservedKeys.IS_FINAL)
                  .collect { case BoolValue(b) =>
                    b
                  }
                  .getOrElse(false)
                (StateMachine.StateId(stateId) -> StateMachine.State(
                  id = StateMachine.StateId(stateId),
                  isFinal = isFinal,
                  metadata = stateMap.get(ReservedKeys.METADATA)
                )).some
              case _ => none
            }
          }
          .map(_.toMap)
      case _ => none
    }

  private def parseTransitions(transitionsValue: JsonLogicValue): Option[List[StateMachine.Transition]] =
    transitionsValue match {
      case ArrayValue(transitionsList) =>
        transitionsList.traverse {
          case MapValue(transMap) =>
            (for {
              fromValue      <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.FROM))
              from           <- OptionT.fromOption[cats.Id](parseStateIdValue(fromValue))
              toValue        <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.TO))
              to             <- OptionT.fromOption[cats.Id](parseStateIdValue(toValue))
              eventTypeValue <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EVENT_TYPE))
              eventType      <- OptionT.fromOption[cats.Id](parseEventTypeValue(eventTypeValue))
              guardValue     <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.GUARD))
              effectValue    <- OptionT.fromOption[cats.Id](transMap.get(ReservedKeys.EFFECT))
            } yield {
              val dependencies = transMap
                .get(ReservedKeys.DEPENDENCIES)
                .collect { case ArrayValue(deps) =>
                  deps.flatMap {
                    case StrValue(id) => scala.util.Try(UUID.fromString(id)).toOption
                    case _            => none
                  }.toSet
                }
                .getOrElse(Set.empty)

              StateMachine.Transition(
                from = from,
                to = to,
                eventType = eventType,
                guard = valueToExpression(guardValue),
                effect = valueToExpression(effectValue),
                dependencies = dependencies
              )
            }).value
          case _ => none
        }
      case _ => none
    }

  private def parseStateIdValue(value: JsonLogicValue): Option[StateMachine.StateId] =
    value match {
      case StrValue(s) => StateMachine.StateId(s).some
      case MapValue(m) => m.get(ReservedKeys.VALUE).collect { case StrValue(s) => StateMachine.StateId(s) }
      case _           => none
    }

  private def parseEventTypeValue(value: JsonLogicValue): Option[StateMachine.EventType] =
    value match {
      case StrValue(et) => StateMachine.EventType(et).some
      case MapValue(m)  => m.get(ReservedKeys.VALUE).collect { case StrValue(et) => StateMachine.EventType(et) }
      case _            => none
    }

  private def extractSpawnDirectivesFromExpression(effectExpr: JsonLogicExpression): List[StateMachine.SpawnDirective] =
    effectExpr match {
      case MapExpression(map) =>
        map
          .get(ReservedKeys.SPAWN)
          .collect { case ArrayExpression(spawns) =>
            spawns.flatMap {
              case MapExpression(spawnMap) =>
                (for {
                  childIdExpr     <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.CHILD_ID))
                  defExpr         <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.DEFINITION))
                  definition      <- OptionT.fromOption[cats.Id](parseStateMachineDefinitionFromExpression(defExpr))
                  initialDataExpr <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.INITIAL_DATA))
                } yield StateMachine.SpawnDirective(
                  childIdExpr = childIdExpr,
                  definition = definition,
                  initialData = initialDataExpr,
                  ownersExpr = spawnMap.get(ReservedKeys.OWNERS)
                )).value.toList
              case _ => List.empty
            }
          }
          .getOrElse(List.empty)

      case ConstExpression(MapValue(map)) =>
        map
          .get(ReservedKeys.SPAWN)
          .collect { case ArrayValue(spawns) =>
            spawns.flatMap {
              case MapValue(spawnMap) =>
                (for {
                  childIdValue     <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.CHILD_ID))
                  defValue         <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.DEFINITION))
                  definition       <- OptionT.fromOption[cats.Id](parseStateMachineDefinition(defValue))
                  initialDataValue <- OptionT.fromOption[cats.Id](spawnMap.get(ReservedKeys.INITIAL_DATA))
                } yield StateMachine.SpawnDirective(
                  childIdExpr = ConstExpression(childIdValue),
                  definition = definition,
                  initialData = ConstExpression(initialDataValue),
                  ownersExpr = spawnMap.get(ReservedKeys.OWNERS).map(ConstExpression(_))
                )).value.toList
              case _ => List.empty
            }
          }
          .getOrElse(List.empty)

      case _ => List.empty
    }

  def processTriggerEventsAtomic[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    triggers:         List[StateMachine.TriggerEvent],
    ordinal:          SnapshotOrdinal,
    baseState:        CalculatedState,
    executionContext: StateMachine.ExecutionContext,
    contextData:      JsonLogicValue,
    gasLimit:         Long
  ): F[StateMachine.TransactionResult] = {

    def processWithTransaction(
      remainingTriggers: List[StateMachine.TriggerEvent],
      txnState:          CalculatedState,
      txnContext:        StateMachine.ExecutionContext
    ): F[StateMachine.TransactionResult] =
      (txnContext.gasUsed >= gasLimit)
        .pure[F]
        .ifM(
          ifTrue = (StateMachine.TransactionResult.Aborted(
            StateMachine.FailureReason.Other(s"Gas exhausted: ${txnContext.gasUsed}"),
            txnContext
          ): StateMachine.TransactionResult).pure[F],
          ifFalse = (txnContext.depth >= txnContext.maxDepth)
            .pure[F]
            .ifM(
              ifTrue = (StateMachine.TransactionResult.Aborted(
                StateMachine.FailureReason.Other(s"Depth exceeded: ${txnContext.depth}"),
                txnContext
              ): StateMachine.TransactionResult).pure[F],
              ifFalse = remainingTriggers match {
                case Nil =>
                  val allStatuses = txnState.records
                    .collect {
                      case (fiberId, r: Records.StateMachineFiberRecord) if r.eventBatch.nonEmpty =>
                        r.eventBatch.map(status => (fiberId, status))
                    }
                    .flatten
                    .toList

                  val updatedFibers = txnState.records.collect { case (fiberId, r: Records.StateMachineFiberRecord) =>
                    fiberId -> r
                  }

                  val updatedOracles = txnState.scriptOracles

                  (StateMachine.TransactionResult
                    .Committed(updatedFibers, updatedOracles, txnContext, allStatuses): StateMachine.TransactionResult)
                    .pure[F]

                case trigger :: _ =>
                  processSingleTrigger(trigger, txnState, txnContext, contextData, gasLimit, ordinal).flatMap {
                    case Right((nextState, nextCtx)) =>
                      processWithTransaction(remainingTriggers.tail, nextState, nextCtx)
                    case Left(reason) =>
                      (StateMachine.TransactionResult.Aborted(reason, txnContext): StateMachine.TransactionResult)
                        .pure[F]
                  }
              }
            )
        )

    processWithTransaction(triggers, baseState, executionContext)
  }

  private def processSingleTrigger[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    trigger:     StateMachine.TriggerEvent,
    state:       CalculatedState,
    ctx:         StateMachine.ExecutionContext,
    contextData: JsonLogicValue,
    gasLimit:    Long,
    ordinal:     SnapshotOrdinal
  ): F[Either[StateMachine.FailureReason, (CalculatedState, StateMachine.ExecutionContext)]] = {
    val isStateMachine = state.stateMachines.contains(trigger.targetMachineId)
    val isOracle = state.scriptOracles.contains(trigger.targetMachineId)

    val sourceFiberId = contextData match {
      case MapValue(m) =>
        m.get(ReservedKeys.MACHINE_ID)
          .collect { case StrValue(id) =>
            scala.util.Try(UUID.fromString(id)).toOption
          }
          .flatten
      case _ => None
    }

    (isStateMachine, isOracle) match {
      case (true, false) =>
        processStateMachineTrigger(trigger, state, ctx, contextData, gasLimit, ordinal)

      case (false, true) =>
        processOracleTrigger(trigger, state, ctx, contextData, ordinal, sourceFiberId)

      case (false, false) =>
        (state, ctx).asRight[StateMachine.FailureReason].pure[F]

      case (true, true) =>
        (StateMachine.FailureReason.Other(s"UUID collision: ${trigger.targetMachineId}"): StateMachine.FailureReason)
          .asLeft[(CalculatedState, StateMachine.ExecutionContext)]
          .pure[F]
    }
  }

  private def processStateMachineTrigger[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    trigger:     StateMachine.TriggerEvent,
    state:       CalculatedState,
    ctx:         StateMachine.ExecutionContext,
    contextData: JsonLogicValue,
    gasLimit:    Long,
    ordinal:     SnapshotOrdinal
  ): F[Either[StateMachine.FailureReason, (CalculatedState, StateMachine.ExecutionContext)]] =
    state.records.get(trigger.targetMachineId).collect { case r: Records.StateMachineFiberRecord => r } match {
      case None =>
        (state, ctx).asRight[StateMachine.FailureReason].pure[F]

      case Some(targetFiber) =>
        (for {
          payloadValue <- EitherT[F, StateMachine.FailureReason, JsonLogicValue](
            JsonLogicEvaluator
              .tailRecursive[F]
              .evaluate(trigger.payloadExpr, contextData, None)
              .map(_.leftMap(err => StateMachine.FailureReason.EffectEvaluationError(err.getMessage)))
          )

          _ <- EitherT.fromEither[F] {
            val validationResult = InputValidation.validateEventPayload(payloadValue)
            Either.cond(
              validationResult.isValid,
              (),
              StateMachine.FailureReason.Other(s"Invalid trigger payload: ${validationResult.errors.mkString(", ")}")
            )
          }

          newEvent = StateMachine.Event(eventType = trigger.eventType, payload = payloadValue)
          newCtx = ctx.copy(depth = ctx.depth + 1, gasUsed = ctx.gasUsed + 5)

          processingResult <- EitherT.liftF[F, StateMachine.FailureReason, StateMachine.ProcessingResult](
            processEvent(targetFiber, newEvent, List.empty, ordinal, state, newCtx, gasLimit)
          )

          successData <- EitherT.fromEither[F](processingResult match {
            case StateMachine.StateMachineSuccess(newState, newStateData, moreTriggers, _, _, _) =>
              (newState, newStateData, moreTriggers).asRight[StateMachine.FailureReason]

            case StateMachine.Failure(reason) =>
              reason.asLeft

            case StateMachine.GasExhausted(_) =>
              StateMachine.FailureReason.Other("Gas exhausted").asLeft

            case StateMachine.DepthExceeded(_) =>
              StateMachine.FailureReason.Other("Depth exceeded").asLeft

            case StateMachine.OracleSuccess(_, _, _) =>
              StateMachine.FailureReason.Other("Unexpected OracleSuccess result for state machine processing").asLeft
          })

          (
            newState: StateMachine.StateId,
            newStateData: JsonLogicValue,
            moreTriggers: List[StateMachine.TriggerEvent]
          ) = successData
          hash <- EitherT.liftF[F, StateMachine.FailureReason, Hash](newStateData.computeDigest)

          status = Records.EventProcessingStatus.Success(
            sequenceNumber = targetFiber.sequenceNumber + 1,
            processedAt = ordinal
          )

          updatedFiber = targetFiber.copy(
            currentState = newState,
            stateData = newStateData,
            stateDataHash = hash,
            sequenceNumber = targetFiber.sequenceNumber + 1,
            lastEventStatus = status,
            eventBatch = targetFiber.eventBatch :+ status
          )

          updatedState = calculatedStateRecordsLens.modify(_.updated(trigger.targetMachineId, updatedFiber))(state)

          finalResult <- EitherT(
            moreTriggers.nonEmpty
              .pure[F]
              .ifM(
                ifTrue =
                  processTriggerEventsAtomic(moreTriggers, ordinal, updatedState, newCtx, contextData, gasLimit).map {
                    case StateMachine.TransactionResult.Committed(fibers, oracles, finalCtx, _) =>
                      val stateWithFibers = fibers.foldLeft(updatedState) { case (s, (fid, fiber)) =>
                        calculatedStateRecordsLens.modify(_.updated(fid, fiber))(s)
                      }
                      val finalState = stateWithFibers.copy(scriptOracles = stateWithFibers.scriptOracles ++ oracles)
                      (finalState, finalCtx).asRight[StateMachine.FailureReason]
                    case StateMachine.TransactionResult.Aborted(reason, _) =>
                      reason.asLeft[(CalculatedState, StateMachine.ExecutionContext)]
                  },
                ifFalse = (updatedState, newCtx).asRight[StateMachine.FailureReason].pure[F]
              )
          )
        } yield finalResult).value
    }

  private def processOracleTrigger[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    trigger:       StateMachine.TriggerEvent,
    state:         CalculatedState,
    ctx:           StateMachine.ExecutionContext,
    contextData:   JsonLogicValue,
    ordinal:       SnapshotOrdinal,
    sourceFiberId: Option[UUID]
  ): F[Either[StateMachine.FailureReason, (CalculatedState, StateMachine.ExecutionContext)]] =
    state.scriptOracles.get(trigger.targetMachineId) match {
      case None =>
        (state, ctx).asRight[StateMachine.FailureReason].pure[F]

      case Some(oracle) =>
        (for {
          callerAddress <- EitherT.fromOption[F](
            sourceFiberId.flatMap { fiberId =>
              state.stateMachines
                .get(fiberId)
                .collect { case r: Records.StateMachineFiberRecord =>
                  r.owners.headOption
                }
                .flatten
            },
            StateMachine.FailureReason.Other("Unable to determine caller for oracle invocation")
          )

          _ <- EitherT[F, StateMachine.FailureReason, Unit](
            OracleProcessor.validateAccess(oracle.accessControl, callerAddress, trigger.targetMachineId)
          )

          payloadValue <- EitherT[F, StateMachine.FailureReason, JsonLogicValue](
            JsonLogicEvaluator
              .tailRecursive[F]
              .evaluate(trigger.payloadExpr, contextData, None)
              .map(_.leftMap(err => StateMachine.FailureReason.EffectEvaluationError(err.getMessage)))
          )

          method = trigger.eventType.value

          inputData = MapValue(
            Map(
              "method"  -> StrValue(method),
              "args"    -> payloadValue,
              "content" -> oracle.stateData.getOrElse(NullValue)
            )
          )

          evaluationResult <- EitherT[F, StateMachine.FailureReason, JsonLogicValue](
            JsonLogicEvaluator
              .tailRecursive[F]
              .evaluate(oracle.scriptProgram, inputData, None)
              .map(_.leftMap(err => StateMachine.FailureReason.EffectEvaluationError(err.getMessage)))
          )

          (newStateData, returnValue) <- EitherT.liftF(
            OracleProcessor.extractStateAndResult(evaluationResult)
          )

          _ <- EitherT.fromEither[F] {
            returnValue match {
              case BoolValue(false) =>
                StateMachine.FailureReason.Other(s"Oracle invocation returned false for method: $method").asLeft
              case MapValue(m) if m.get("valid").contains(BoolValue(false)) =>
                val errorMsg = m.get("error").collect { case StrValue(e) => e }.getOrElse("Unknown error")
                StateMachine.FailureReason.Other(s"Oracle validation failed for method $method: $errorMsg").asLeft
              case _ => ().asRight
            }
          }

          newHash <- EitherT.liftF[F, StateMachine.FailureReason, Option[Hash]](
            newStateData.traverse(_.computeDigest)
          )

          invocation = Records.OracleInvocation(
            method = method,
            args = payloadValue,
            result = returnValue,
            gasUsed = 10L,
            invokedAt = ordinal,
            invokedBy = callerAddress
          )

          newLog = (invocation :: oracle.invocationLog).take(oracle.maxLogSize)

          updatedOracle = oracle.copy(
            stateData = newStateData,
            stateDataHash = newHash,
            latestUpdateOrdinal = ordinal,
            invocationCount = oracle.invocationCount + 1,
            invocationLog = newLog
          )

          updatedState = state.copy(
            scriptOracles = state.scriptOracles.updated(trigger.targetMachineId, updatedOracle)
          )

          newCtx = ctx.copy(gasUsed = ctx.gasUsed + 10)

        } yield (updatedState, newCtx)).value
    }
}
