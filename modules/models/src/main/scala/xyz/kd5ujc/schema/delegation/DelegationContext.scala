package xyz.kd5ujc.schema.delegation

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.fiber.ReservedKeys

/**
 * Builds JLVM context values for delegation state.
 *
 * The resulting `MapValue` is merged into the fiber evaluation context so that
 * delegation predicates from [[DelegationPredicates]] can access delegation
 * state via `{"var": "delegation.active"}` etc.
 *
 * === Context shape ===
 * {{{
 * {
 *   "delegation": {
 *     "active": true,
 *     "expiresAt": 12345,
 *     "scope": ["market", "contract"],
 *     "spendLimit": 5000,
 *     "spendUsed": 1200,
 *     "spendRemaining": 3800,
 *     "delegator": "DAG...",
 *     "relayer": "DAG...",
 *     "sessionKey": "0x...",
 *     "bondedStake": 2000
 *   }
 * }
 * }}}
 *
 * When there is no active delegation (non-delegated call), the context contains:
 * {{{
 * { "delegation": { "active": false } }
 * }}}
 */
object DelegationContext {

  /**
   * Build delegation context for an active delegation.
   *
   * @param credential    The active delegation credential
   * @param currentOrdinal Current snapshot ordinal (used for expiry check)
   */
  def fromCredential(credential: DelegationCredential, currentOrdinal: Long): MapValue = {
    val delegationData = MapValue(
      Map(
        key(ReservedKeys.DELEGATION_ACTIVE)       -> BoolValue(credential.isActive(currentOrdinal)),
        key(ReservedKeys.DELEGATION_EXPIRES)      -> IntValue(credential.expiresAtOrdinal),
        key(ReservedKeys.DELEGATION_SCOPE)        -> ArrayValue(credential.scope.map(StrValue(_))),
        key(ReservedKeys.DELEGATION_SPEND_LIMIT)  -> IntValue(credential.spendLimit),
        key(ReservedKeys.DELEGATION_SPEND_USED)   -> IntValue(credential.spendUsed),
        key(ReservedKeys.DELEGATION_SPEND_REMAIN) -> IntValue(credential.spendRemaining),
        key(ReservedKeys.DELEGATION_DELEGATOR)    -> StrValue(credential.delegatorAddr),
        key(ReservedKeys.DELEGATION_RELAYER)      -> StrValue(credential.relayerAddr),
        key(ReservedKeys.DELEGATION_SESSION_KEY)  -> StrValue(credential.sessionKey),
        key(ReservedKeys.DELEGATION_BONDED_STAKE) -> IntValue(credential.stakeBonded)
      )
    )
    MapValue(Map("delegation" -> delegationData))
  }

  /**
   * Build a null/inactive delegation context (no delegation present).
   * Ensures delegation predicates evaluate to false gracefully.
   */
  val noDelegation: MapValue =
    MapValue(Map("delegation" -> MapValue(Map(key(ReservedKeys.DELEGATION_ACTIVE) -> BoolValue(false)))))

  // Strip the "delegation." prefix from ReservedKeys to get the local map key
  private def key(fullKey: String): String =
    if (fullKey.startsWith("delegation.")) fullKey.drop("delegation.".length)
    else fullKey

  /**
   * Merge delegation context into an existing JLVM context value.
   */
  def mergeInto(base: JsonLogicValue, delegationCtx: MapValue): JsonLogicValue = base match {
    case MapValue(existing) => MapValue(existing ++ delegationCtx.value)
    case other              => other // non-map context: return unchanged
  }
}
