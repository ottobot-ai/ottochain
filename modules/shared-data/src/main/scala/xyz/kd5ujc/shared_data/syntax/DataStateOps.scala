package xyz.kd5ujc.shared_data.syntax

import java.util.UUID

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records}

/**
 * Extension methods for DataState to simplify state updates.
 *
 * These operations ensure that both OnChain (hashes) and CalculatedState (records)
 * are updated atomically, preventing inconsistencies.
 */
trait DataStateOps {

  implicit class DataStateSyntax(private val state: DataState[OnChain, CalculatedState]) {

    /**
     * Update a fiber record and its hash in both OnChain and CalculatedState.
     *
     * @param id    The fiber's CID
     * @param fiber The updated fiber record
     * @param hash  The hash of the fiber's state data
     * @return Updated DataState with both OnChain and CalculatedState modified
     */
    def withFiber(id: UUID, fiber: Records.StateMachineFiberRecord, hash: Hash): DataState[OnChain, CalculatedState] =
      DataState(
        state.onChain.copy(latest = state.onChain.latest.updated(id, hash)),
        state.calculated.copy(stateMachines = state.calculated.stateMachines.updated(id, fiber))
      )

    /**
     * Update an oracle record and optionally its hash in both OnChain and CalculatedState.
     *
     * @param id     The oracle's CID
     * @param oracle The updated oracle record
     * @param hash   Optional hash of the oracle's state data (None if no state)
     * @return Updated DataState with both OnChain and CalculatedState modified
     */
    def withOracle(
      id:     UUID,
      oracle: Records.ScriptOracleFiberRecord,
      hash:   Option[Hash]
    ): DataState[OnChain, CalculatedState] =
      DataState(
        hash.fold(state.onChain)(h => state.onChain.copy(latest = state.onChain.latest.updated(id, h))),
        state.calculated.copy(scriptOracles = state.calculated.scriptOracles.updated(id, oracle))
      )

    /**
     * Apply multiple fiber updates from a map.
     *
     * Useful for applying batch updates from FiberOrchestrator outcomes.
     * Hashes are extracted from each fiber's stateDataHash field.
     *
     * @param fibers Map of fiber IDs to updated fiber records
     * @return Updated DataState with all fibers applied
     */
    def withFibers(fibers: Map[UUID, Records.StateMachineFiberRecord]): DataState[OnChain, CalculatedState] = {
      val hashes = fibers.map { case (id, f) => id -> f.stateDataHash }
      DataState(
        state.onChain.copy(latest = state.onChain.latest ++ hashes),
        state.calculated.copy(stateMachines = state.calculated.stateMachines ++ fibers)
      )
    }

    /**
     * Apply multiple oracle updates from a map.
     *
     * Hashes are extracted from each oracle's stateDataHash field if present.
     *
     * @param oracles Map of oracle IDs to updated oracle records
     * @return Updated DataState with all oracles applied
     */
    def withOracles(oracles: Map[UUID, Records.ScriptOracleFiberRecord]): DataState[OnChain, CalculatedState] = {
      val hashes = oracles.flatMap { case (id, o) => o.stateDataHash.map(id -> _) }
      DataState(
        state.onChain.copy(latest = state.onChain.latest ++ hashes),
        state.calculated.copy(scriptOracles = state.calculated.scriptOracles ++ oracles)
      )
    }

    /**
     * Apply both fiber and oracle updates atomically.
     *
     * Useful for transaction outcomes that affect multiple entities.
     *
     * @param fibers  Map of fiber IDs to updated fiber records
     * @param oracles Map of oracle IDs to updated oracle records
     * @return Updated DataState with all entities applied
     */
    def withFibersAndOracles(
      fibers:  Map[UUID, Records.StateMachineFiberRecord],
      oracles: Map[UUID, Records.ScriptOracleFiberRecord]
    ): DataState[OnChain, CalculatedState] = {
      val fiberHashes = fibers.map { case (id, f) => id -> f.stateDataHash }
      val oracleHashes = oracles.flatMap { case (id, o) => o.stateDataHash.map(id -> _) }
      DataState(
        state.onChain.copy(latest = state.onChain.latest ++ fiberHashes ++ oracleHashes),
        state.calculated.copy(
          stateMachines = state.calculated.stateMachines ++ fibers,
          scriptOracles = state.calculated.scriptOracles ++ oracles
        )
      )
    }
  }
}

object DataStateOps extends DataStateOps
