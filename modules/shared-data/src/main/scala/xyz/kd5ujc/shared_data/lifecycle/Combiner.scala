package xyz.kd5ujc.shared_data.lifecycle

import java.util.UUID

import cats.effect.{Async, Sync}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.lifecycle.CombinerService
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}

import eu.timepit.refined.refineV
import monocle.Monocle.toAppliedFocusOps

object Combiner {

  def make[F[_]: Async: SecurityProvider]: CombinerService[F, OttochainMessage, OnChain, CalculatedState] =
    new CombinerService[F, OttochainMessage, OnChain, CalculatedState] {

      override def insert(
        previous: DataState[OnChain, CalculatedState],
        update:   Signed[OttochainMessage]
      )(implicit ctx: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] = {
        implicit val jsonLogicEvaluator = JsonLogicEvaluator.tailRecursive[F]
        val combiner = new CombinerImpl[F](previous, ctx)

        update.value match {
          case u: Updates.CreateStateMachineFiber => combiner.createStateMachineFiber(Signed(u, update.proofs))
          case u: Updates.ProcessFiberEvent       => combiner.processFiberEvent(Signed(u, update.proofs))
          case u: Updates.ArchiveFiber            => combiner.archiveFiber(Signed(u, update.proofs))
          case u: Updates.CreateScriptOracle      => combiner.createScriptOracle(Signed(u, update.proofs))
          case u: Updates.InvokeScriptOracle      => combiner.invokeScriptOracle(Signed(u, update.proofs))
        }
      }
    }

  private class CombinerImpl[F[_]: Async: SecurityProvider: JsonLogicEvaluator](
    current: DataState[OnChain, CalculatedState],
    ctx:     L0NodeContext[F]
  ) {

    def createStateMachineFiber(
      update: Signed[Updates.CreateStateMachineFiber]
    ): F[DataState[OnChain, CalculatedState]] = for {
      currentOrdinal <- getCurrentOrdinal
      owners         <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from(_))

      // Hash initial data - convert to Json first
      initialDataHash <- update.initialData.computeDigest

      // Create fiber record
      _record = Records.StateMachineFiberRecord(
        cid = update.cid,
        creationOrdinal = currentOrdinal,
        previousUpdateOrdinal = currentOrdinal,
        latestUpdateOrdinal = currentOrdinal,
        definition = update.definition,
        currentState = update.definition.initialState,
        stateData = update.initialData,
        stateDataHash = initialDataHash,
        sequenceNumber = 0,
        owners = owners,
        status = Records.FiberStatus.Active,
        lastEventStatus = Records.EventProcessingStatus.Initialized,
        parentFiberId = update.parentFiberId
      )

      _onchain <- current.onChain
        .focus(_.latest)
        .modify(_.updated(update.cid, initialDataHash))
        .pure[F]

      _calculated <- current.calculated
        .focus(_.stateMachines)
        .modify(_.updated(update.cid, _record))
        .pure[F]

    } yield DataState(_onchain, _calculated)

    def processFiberEvent(
      update: Signed[Updates.ProcessFiberEvent]
    ): F[DataState[OnChain, CalculatedState]] = for {
      currentOrdinal <- getCurrentOrdinal

      // Get existing fiber record
      fiberRecord <- current.calculated.stateMachines
        .get(update.cid)
        .collect { case r: Records.StateMachineFiberRecord => r }
        .fold(
          Async[F].raiseError[Records.StateMachineFiberRecord](
            new RuntimeException(s"Fiber ${update.cid} not found")
          )
        )(_.pure[F])

      // Check fiber is active
      _ <- Async[F].whenA(fiberRecord.status != Records.FiberStatus.Active)(
        Async[F].raiseError(new RuntimeException(s"Fiber ${update.cid} is not active"))
      )

      // Create execution context for processing
      initialContext = StateMachine.ExecutionContext(
        depth = 0,
        maxDepth = 10,
        gasUsed = 0L,
        maxGas = 10_000_000L,
        processedEvents = Set.empty
      )

      // Process the event
      proofsList = update.proofs.toList
      gasLimit = 10_000_000L
      result <- DeterministicEventProcessor.processEvent(
        fiberRecord,
        update.event,
        proofsList,
        currentOrdinal,
        current.calculated,
        initialContext,
        gasLimit
      )

      // Handle processing result
      newState <- result match {
        case StateMachine.StateMachineSuccess(newStateId, newStateData, triggerEvents, spawnMachines, _, _) =>
          for {
            newHash <- newStateData.computeDigest
            newSequenceNumber = fiberRecord.sequenceNumber + 1

            parentStatus = Records.EventProcessingStatus.Success(newSequenceNumber, currentOrdinal)

            updatedFiber = fiberRecord.copy(
              previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
              latestUpdateOrdinal = currentOrdinal,
              currentState = newStateId,
              stateData = newStateData,
              stateDataHash = newHash,
              sequenceNumber = newSequenceNumber,
              lastEventStatus = parentStatus,
              eventBatch = List(parentStatus)
            )

            // Process spawns BEFORE triggers (so triggers can reference new children)
            // Use updated fiber for context so spawned children see parent's new sequence number
            contextForSpawn <- buildTriggerContext(updatedFiber, update.event)
            spawnedFibers <- spawnMachines.traverse { spawn =>
              processSpawn(spawn, updatedFiber, currentOrdinal, contextForSpawn)
            }

            // Update parent's childFiberIds
            parentWithChildren = updatedFiber.copy(
              childFiberIds = updatedFiber.childFiberIds ++ spawnedFibers.map(_.cid)
            )

            // Build state with spawned children
            stateWithSpawns = spawnedFibers.foldLeft(
              current.calculated.copy(
                stateMachines = current.calculated.stateMachines.updated(update.cid, parentWithChildren)
              )
            ) { case (state, child) =>
              state.copy(
                stateMachines = state.stateMachines.updated(child.cid, child)
              )
            }

            // Process triggers with updated state (includes spawned children)
            txnResult <- triggerEvents.nonEmpty
              .pure[F]
              .ifM(
                ifTrue = buildTriggerContext(parentWithChildren, update.event).flatMap { ctx =>
                  DeterministicEventProcessor.processTriggerEventsAtomic(
                    triggerEvents,
                    currentOrdinal,
                    stateWithSpawns,
                    initialContext.copy(depth = 1),
                    ctx,
                    gasLimit
                  )
                },
                ifFalse = {
                  // No triggers, but we still need to commit parent + all spawned children
                  val allFibers = Map(update.cid -> parentWithChildren) ++ spawnedFibers.map(f => f.cid -> f).toMap
                  (StateMachine.TransactionResult.Committed(
                    allFibers,
                    Map.empty,
                    initialContext,
                    List((update.cid, parentStatus))
                  ): StateMachine.TransactionResult).pure[F]
                }
              )

            finalState <- txnResult match {
              case StateMachine.TransactionResult.Committed(updatedFibers, updatedOracles, finalCtx, allStatuses) =>
                for {
                  // Merge updated fibers from triggers with ALL spawned children
                  // Start with spawnedFibers (initial state), then overlay updatedFibers (triggered updates win)
                  allFibersToCommit <- (spawnedFibers.map(f => f.cid -> f).toMap ++ updatedFibers).pure[F]

                  // First, apply updated fibers
                  stateWithFibers = allFibersToCommit.foldLeft(current.calculated) { case (state, (fiberId, fiber)) =>
                    val fiberStatuses = allStatuses.filter(_._1 == fiberId).map(_._2)

                    // Ensure parent fiber retains childFiberIds after trigger processing
                    val fiberWithCorrectChildren = if (fiberId == update.cid) {
                      fiber.copy(
                        childFiberIds = fiber.childFiberIds ++ spawnedFibers.map(_.cid),
                        eventBatch = fiberStatuses
                      )
                    } else {
                      // For spawned children, preserve their initial status if they weren't triggered
                      val statusesToUse = if (fiberStatuses.nonEmpty) fiberStatuses else List(fiber.lastEventStatus)
                      fiber.copy(eventBatch = statusesToUse)
                    }

                    state.copy(
                      stateMachines = state.stateMachines.updated(fiberId, fiberWithCorrectChildren)
                    )
                  }

                  // Then, apply updated script oracles
                  finalCalcState = stateWithFibers.copy(
                    scriptOracles = stateWithFibers.scriptOracles ++ updatedOracles
                  )

                  // Update hashes for all fibers (including untriggered spawned children)
                  updatedHashes = allFibersToCommit.map { case (fid, fiber) => fid -> fiber.stateDataHash }

                  // Update hashes for script oracles
                  oracleHashes = updatedOracles.flatMap { case (oid, oracle) => oracle.stateDataHash.map(oid -> _) }

                  _onchain = current.onChain.copy(latest = current.onChain.latest ++ updatedHashes ++ oracleHashes)
                } yield DataState(_onchain, finalCalcState)

              case StateMachine.TransactionResult.Aborted(reason, failCtx) =>
                for {
                  statusHash <- fiberRecord.stateData.computeDigest

                  failedStatus = Records.EventProcessingStatus.ExecutionFailed(
                    reason = reason.toMessage,
                    attemptedAt = currentOrdinal,
                    attemptedEventType = update.event.eventType,
                    gasUsed = failCtx.gasUsed,
                    depth = failCtx.depth
                  )

                  failedFiber = fiberRecord.copy(
                    previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
                    latestUpdateOrdinal = currentOrdinal,
                    lastEventStatus = failedStatus,
                    eventBatch = List(failedStatus)
                  )

                  _calculated = current.calculated.copy(
                    stateMachines = current.calculated.stateMachines.updated(update.cid, failedFiber)
                  )

                  _onchain = current.onChain.copy(
                    latest = current.onChain.latest.updated(update.cid, statusHash)
                  )

                } yield DataState(_onchain, _calculated)
            }

          } yield finalState

        case StateMachine.Failure(StateMachine.FailureReason.NoGuardMatched(fromState, eventType, attemptedGuards)) =>
          for {
            statusHash <- fiberRecord.stateData.computeDigest

            guardFailedStatus = Records.EventProcessingStatus.GuardFailed(
              reason =
                s"No guard matched from ${fromState.value} on ${eventType.value} ($attemptedGuards guards tried)",
              attemptedAt = currentOrdinal,
              attemptedEventType = update.event.eventType
            )

            updatedFiber = fiberRecord.copy(
              previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
              latestUpdateOrdinal = currentOrdinal,
              lastEventStatus = guardFailedStatus,
              eventBatch = List(guardFailedStatus)
            )

            _calculated = current.calculated.copy(
              stateMachines = current.calculated.stateMachines.updated(update.cid, updatedFiber)
            )

            _onchain = current.onChain.copy(
              latest = current.onChain.latest.updated(update.cid, statusHash)
            )

          } yield DataState(_onchain, _calculated)

        case StateMachine.Failure(reason) =>
          Async[F].raiseError(new RuntimeException(s"Event processing failed: $reason"))

        case StateMachine.GasExhausted(gas) =>
          for {
            statusHash <- fiberRecord.stateData.computeDigest

            gasExhaustedStatus = Records.EventProcessingStatus.ExecutionFailed(
              reason = s"Gas exhausted: $gas",
              attemptedAt = currentOrdinal,
              attemptedEventType = update.event.eventType,
              gasUsed = gas,
              depth = initialContext.depth
            )

            failedFiber = fiberRecord.copy(
              previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
              latestUpdateOrdinal = currentOrdinal,
              lastEventStatus = gasExhaustedStatus,
              eventBatch = List(gasExhaustedStatus)
            )

            _calculated = current.calculated.copy(
              stateMachines = current.calculated.stateMachines.updated(update.cid, failedFiber)
            )

            _onchain = current.onChain.copy(
              latest = current.onChain.latest.updated(update.cid, statusHash)
            )

          } yield DataState(_onchain, _calculated)

        case StateMachine.DepthExceeded(depth) =>
          for {
            statusHash <- fiberRecord.stateData.computeDigest

            depthExceededStatus = Records.EventProcessingStatus.ExecutionFailed(
              reason = s"Max depth exceeded: $depth",
              attemptedAt = currentOrdinal,
              attemptedEventType = update.event.eventType,
              gasUsed = initialContext.gasUsed,
              depth = depth
            )

            failedFiber = fiberRecord.copy(
              previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
              latestUpdateOrdinal = currentOrdinal,
              lastEventStatus = depthExceededStatus,
              eventBatch = List(depthExceededStatus)
            )

            _calculated = current.calculated.copy(
              stateMachines = current.calculated.stateMachines.updated(update.cid, failedFiber)
            )

            _onchain = current.onChain.copy(
              latest = current.onChain.latest.updated(update.cid, statusHash)
            )

          } yield DataState(_onchain, _calculated)

        case StateMachine.OracleSuccess(_, _, _) =>
          Async[F].raiseError(
            new RuntimeException("Unexpected OracleSuccess result for state machine event processing")
          )
      }

    } yield newState

    def archiveFiber(
      update: Signed[Updates.ArchiveFiber]
    ): F[DataState[OnChain, CalculatedState]] = for {
      currentOrdinal <- getCurrentOrdinal

      // Get existing fiber
      fiberRecord <- current.calculated.stateMachines
        .get(update.cid)
        .collect { case r: Records.StateMachineFiberRecord => r }
        .fold(
          Async[F].raiseError[Records.StateMachineFiberRecord](
            new RuntimeException(s"Fiber ${update.cid} not found")
          )
        )(_.pure[F])

      // Update status to archived
      updatedFiber = fiberRecord.copy(
        previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
        latestUpdateOrdinal = currentOrdinal,
        status = Records.FiberStatus.Archived
      )

      _calculated <- current.calculated
        .focus(_.stateMachines)
        .modify(_.updated(update.cid, updatedFiber))
        .pure[F]

    } yield DataState(current.onChain, _calculated)

    private def getCurrentOrdinal: F[SnapshotOrdinal] =
      ctx.getLastCurrencySnapshot.flatMap { latestSnapshot =>
        Sync[F].fromOption(
          latestSnapshot.map(_.signed.value.ordinal.next),
          new RuntimeException("Combiner failed to retrieve latest currency snapshot!")
        )
      }

    private def buildTriggerContext(
      fiber: Records.StateMachineFiberRecord,
      event: StateMachine.Event
    ): F[JsonLogicValue] = for {
      // Build parent context
      parentData <- fiber.parentFiberId match {
        case Some(parentId) =>
          current.calculated.stateMachines
            .get(parentId)
            .collect { case r: Records.StateMachineFiberRecord => r }
            .map { parentFiber =>
              MapValue(
                Map(
                  "state"          -> parentFiber.stateData,
                  "currentStateId" -> StrValue(parentFiber.currentState.value),
                  "sequenceNumber" -> IntValue(parentFiber.sequenceNumber),
                  "machineId"      -> StrValue(parentFiber.cid.toString)
                )
              ): JsonLogicValue
            }
            .getOrElse(NullValue: JsonLogicValue)
            .pure[F]
        case None =>
          (NullValue: JsonLogicValue).pure[F]
      }

      // Build children context
      childrenData <- fiber.childFiberIds.toList
        .traverse { childId =>
          current.calculated.stateMachines
            .get(childId)
            .collect { case r: Records.StateMachineFiberRecord => r }
            .traverse { childFiber =>
              MapValue(
                Map(
                  "state"          -> childFiber.stateData,
                  "currentStateId" -> StrValue(childFiber.currentState.value),
                  "sequenceNumber" -> IntValue(childFiber.sequenceNumber)
                )
              ).pure[F].map(childId.toString -> _)
            }
        }
        .map(_.flatten.toMap)
        .map(MapValue(_))
    } yield (MapValue(
      Map(
        "state"          -> fiber.stateData,
        "event"          -> event.payload,
        "eventType"      -> StrValue(event.eventType.value),
        "machineId"      -> StrValue(fiber.cid.toString),
        "currentStateId" -> StrValue(fiber.currentState.value),
        "sequenceNumber" -> IntValue(fiber.sequenceNumber),
        "parent"         -> parentData,
        "children"       -> childrenData
      )
    ): JsonLogicValue)

    private def processSpawn(
      spawn:          StateMachine.SpawnDirective,
      parentFiber:    Records.StateMachineFiberRecord,
      currentOrdinal: SnapshotOrdinal,
      contextData:    JsonLogicValue
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
            Async[F].raiseError[String](new RuntimeException(s"childId must evaluate to string, got: $childIdValue"))
        }
        childId <- scala.util.Try(UUID.fromString(childIdStr)).toOption match {
          case Some(uuid) => uuid.pure[F]
          case None       => Async[F].raiseError[UUID](new RuntimeException(s"Invalid UUID format: $childIdStr"))
        }

        // The initialData expression was already evaluated during effect processing in DeterministicEventProcessor
        // It's wrapped as a ConstExpression, so we need to evaluate it (which just unwraps the constant)
        // BUT we should evaluate it with the CURRENT context to get proper variable substitution
        // Actually, spawn.initialData is a ConstExpression wrapping the ALREADY EVALUATED value from the effect
        // So we should just use it directly by evaluating with the spawn context
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
          creationOrdinal = currentOrdinal,
          previousUpdateOrdinal = currentOrdinal,
          latestUpdateOrdinal = currentOrdinal,
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

    def createScriptOracle(
      update: Signed[Updates.CreateScriptOracle]
    ): F[DataState[OnChain, CalculatedState]] = for {
      currentOrdinal <- getCurrentOrdinal
      result         <- OracleProcessor.createScriptOracle(current, update, currentOrdinal)
    } yield result

    def invokeScriptOracle(
      update: Signed[Updates.InvokeScriptOracle]
    ): F[DataState[OnChain, CalculatedState]] = for {
      currentOrdinal <- getCurrentOrdinal

      oracleRecord <- current.calculated.scriptOracles
        .get(update.cid)
        .fold(
          Async[F].raiseError[Records.ScriptOracleFiberRecord](
            new RuntimeException(s"Oracle ${update.cid} not found")
          )
        )(_.pure[F])

      caller <- update.proofs.toList.headOption
        .fold(Async[F].raiseError[Address](new RuntimeException("No proof provided")))(
          _.id.toAddress
        )

      processingResult <- OracleProcessor.invokeScriptOracle(current, update, currentOrdinal)

      newState <- processingResult match {
        case StateMachine.OracleSuccess(newStateData, returnValue, gasUsed) =>
          for {
            newStateHash <- newStateData.traverse(_.computeDigest)

            invocation = Records.OracleInvocation(
              method = update.method,
              args = update.args,
              result = returnValue,
              gasUsed = gasUsed,
              invokedAt = currentOrdinal,
              invokedBy = caller
            )

            updatedLog = (invocation :: oracleRecord.invocationLog).take(oracleRecord.maxLogSize)

            updatedOracle = oracleRecord.copy(
              stateData = newStateData,
              stateDataHash = newStateHash,
              latestUpdateOrdinal = currentOrdinal,
              invocationCount = oracleRecord.invocationCount + 1,
              invocationLog = updatedLog
            )

            _calculated = current.calculated.copy(
              scriptOracles = current.calculated.scriptOracles.updated(update.cid, updatedOracle)
            )

            _onchain = newStateHash.fold(current.onChain) { hash =>
              current.onChain
                .focus(_.latest)
                .modify(_.updated(update.cid, hash))
            }
          } yield DataState(_onchain, _calculated)

        case StateMachine.Failure(reason) =>
          Async[F].raiseError(new RuntimeException(s"Oracle invocation failed: $reason"))

        case other =>
          Async[F].raiseError(new RuntimeException(s"Unexpected oracle result: $other"))
      }
    } yield newState
  }
}
