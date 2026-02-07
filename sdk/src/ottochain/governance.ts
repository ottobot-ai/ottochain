/**
 * Governance and DAO type definitions
 *
 * TypeScript interfaces for governance state machines and DAO configurations.
 * These types represent the on-chain governance primitives: voting, proposals,
 * delegations, and multi-branch governance structures.
 *
 * @see governance/*.json for JSON state machine definitions
 * @packageDocumentation
 */

import type { Address, StateId, StateMachineDefinition, JsonLogicValue } from './types.js';

// ---------------------------------------------------------------------------
// Governance Types
// ---------------------------------------------------------------------------

/**
 * Type of governance structure.
 * Maps to the separation of powers model.
 */
export type GovernanceType =
  | 'Legislature'   // Creates laws/proposals, votes on policy
  | 'Executive'     // Implements mandates, manages operations
  | 'Judiciary'     // Interprets rules, resolves disputes, issues rulings
  | 'Constitution'  // Foundational rules, amendment process
  | 'Simple';       // Basic org governance without branch separation

/**
 * Type of DAO voting mechanism.
 */
export type DAOType =
  | 'Single'     // Single admin with full control
  | 'Multisig'   // N-of-M threshold signing
  | 'Threshold'  // Percentage-based approval threshold
  | 'Token';     // Token-weighted voting

// ---------------------------------------------------------------------------
// Proposal State
// ---------------------------------------------------------------------------

/**
 * Current state of a proposal in the governance lifecycle.
 */
export type ProposalStatus =
  | 'Draft'       // Being prepared, not yet submitted
  | 'Active'      // Open for voting
  | 'Pending'     // Passed, in veto/timelock period
  | 'Queued'      // Awaiting execution after timelock
  | 'Executing'   // Execution in progress
  | 'Executed'    // Successfully executed
  | 'Defeated'    // Failed to pass (quorum/threshold not met)
  | 'Vetoed'      // Blocked by guardian/veto holder
  | 'Cancelled'   // Cancelled by proposer
  | 'Expired';    // Exceeded time limits

/**
 * Proposal action to be executed if passed.
 */
export interface ProposalAction {
  /** Target fiber ID to invoke */
  targetFiberId: string;
  /** Event name to trigger on target */
  eventName: string;
  /** Payload for the event */
  payload: JsonLogicValue;
}

/**
 * Full proposal state.
 */
export interface ProposalState {
  /** Unique proposal ID */
  id: string;
  /** Human-readable title */
  title: string;
  /** Detailed description */
  description: string;
  /** URL to discussion forum/thread */
  discussionUrl?: string;
  /** Actions to execute if passed */
  actions: ProposalAction[];
  /** Address that created the proposal */
  proposer: Address;
  /** Current status */
  status: ProposalStatus;
  /** When proposal was submitted for voting */
  submittedAt?: string;
  /** When voting period ends */
  votingEndsAt?: string;
  /** When veto period ends */
  vetoEndsAt?: string;
  /** When proposal becomes executable (after timelock) */
  executableAt?: string;
  /** Block/ordinal at which voting power snapshot was taken */
  snapshotBlock?: number;
}

// ---------------------------------------------------------------------------
// Voting
// ---------------------------------------------------------------------------

/**
 * Vote choice.
 */
export type VoteChoice = 'For' | 'Against' | 'Abstain';

/**
 * Record of a single vote cast.
 */
export interface VoteRecord {
  /** Address of the voter */
  voter: Address;
  /** Vote choice */
  choice: VoteChoice;
  /** Voting weight (tokens, stake, or 1 for one-person-one-vote) */
  weight: number;
  /** Conviction multiplier for conviction voting */
  conviction?: number;
  /** If vote was cast on behalf of delegator */
  delegatedFrom?: Address;
  /** Timestamp when vote was cast */
  votedAt: string;
}

/**
 * Aggregated vote tally.
 */
export interface VoteTally {
  /** Total weight of For votes */
  for: number;
  /** Total weight of Against votes */
  against: number;
  /** Total weight of Abstain votes */
  abstain: number;
  /** Whether quorum was reached */
  quorumReached: boolean;
  /** Whether proposal passed */
  passed: boolean;
  /** Reason for defeat if not passed */
  reason?: string;
}

/**
 * Delegation of voting power.
 */
export interface Delegation {
  /** Address delegating their voting power */
  delegator: Address;
  /** Address receiving the delegation */
  delegateTo: Address;
  /** Weight being delegated */
  weight: number;
  /** When delegation was created */
  delegatedAt: string;
}

// ---------------------------------------------------------------------------
// DAO Configuration
// ---------------------------------------------------------------------------

/**
 * Voting mechanism type.
 */
export type VotingMechanism =
  | 'simple'       // Simple majority
  | 'supermajority' // Requires > 2/3 or custom threshold
  | 'quadratic'    // Vote weight = sqrt(tokens)
  | 'conviction'   // Time-weighted conviction voting
  | 'ranked';      // Ranked choice voting

/**
 * Quorum calculation type.
 */
export type QuorumType =
  | 'percentage'   // Percentage of total voting power
  | 'absolute';    // Absolute number of votes required

/**
 * DAO governance configuration.
 */
export interface DAOConfig {
  /** How votes are weighted and counted */
  votingMechanism: VotingMechanism;
  /** How quorum is calculated */
  quorumType: QuorumType;
  /** Quorum value (percentage 0-1 or absolute count) */
  quorumValue: number;
  /** Threshold to pass (percentage 0-1) */
  passingThreshold: number;
  /** Duration of voting period in milliseconds */
  votingPeriodMs: number;
  /** Duration of veto period in milliseconds (0 for no veto) */
  vetoPeriodMs: number;
  /** Grace period for finalization in milliseconds */
  gracePeriodMs: number;
  /** Duration of timelock before execution in milliseconds */
  timelockMs?: number;
  /** Whether delegation is allowed */
  allowDelegation: boolean;
  /** Whether anyone can vote (vs. whitelist) */
  openVoting: boolean;
  /** One person one vote regardless of holdings */
  onePersonOneVote: boolean;
  /** Half-life for conviction decay in milliseconds */
  convictionHalfLifeMs?: number;
  /** Minimum tokens required to create proposal */
  proposalThreshold?: number;
}

/**
 * DAO role configuration.
 */
export interface DAORoles {
  /** Addresses allowed to create proposals (empty = open) */
  proposers: Address[];
  /** Addresses allowed to vote (empty = open or token-weighted) */
  voters: Address[];
  /** Addresses allowed to execute passed proposals */
  executors: Address[];
  /** Addresses allowed to veto (guardians) */
  vetoers: Address[];
  /** Admin addresses for configuration changes */
  admins: Address[];
}

/**
 * Multisig-specific configuration.
 */
export interface MultisigConfig {
  /** Required number of signatures */
  threshold: number;
  /** Authorized signers */
  signers: Address[];
  /** Time-to-live for proposals in milliseconds */
  proposalTTLMs: number;
}

/**
 * Token DAO-specific configuration.
 */
export interface TokenDAOConfig extends DAOConfig {
  /** Token fiber ID for balance queries */
  tokenId: string;
  /** Current token balances (address -> amount) */
  balances: Record<string, number>;
  /** Current delegations (delegator -> delegate) */
  delegations: Record<string, string>;
}

// ---------------------------------------------------------------------------
// Governance Definition Wrapper
// ---------------------------------------------------------------------------

/**
 * Complete governance definition with state machine and configuration.
 */
export interface GovernanceDefinition {
  /** Type of governance structure */
  governanceType: GovernanceType;
  /** Type of DAO (for DAO governance types) */
  daoType?: DAOType;
  /** State machine definition (from JSON) */
  stateMachine: StateMachineDefinition;
  /** Governance configuration */
  config: DAOConfig;
  /** Role assignments */
  roles: DAORoles;
  /** Constitution fiber ID (for separation of powers) */
  constitutionId?: string;
  /** Metadata */
  metadata?: {
    name: string;
    description?: string;
    version?: string;
  };
}

// ---------------------------------------------------------------------------
// Governance State (on-chain)
// ---------------------------------------------------------------------------

/**
 * Current state of a DAO governance fiber.
 */
export interface DAOState {
  /** Schema identifier for indexing */
  schema: 'Governance' | 'MultisigDAO' | 'TokenDAO' | string;
  /** DAO name */
  name: string;
  /** Current governance status */
  status: 'ACTIVE' | 'VOTING' | 'PENDING' | 'DISSOLVED' | string;
  /** Current proposal (if any) */
  proposal: ProposalState | null;
  /** Current votes */
  votes: Record<string, VoteRecord> | VoteTally | null;
  /** Current delegations */
  delegations?: Record<string, Delegation>;
  /** Configuration */
  config: DAOConfig | MultisigConfig;
  /** Roles */
  roles?: DAORoles;
  /** History of executed proposals */
  executedProposals?: ProposalState[];
  /** History of rejected proposals */
  rejectedProposals?: ProposalState[];
  /** History of cancelled proposals */
  cancelledProposals?: ProposalState[];
}

/**
 * Multisig DAO state.
 */
export interface MultisigDAOState extends DAOState {
  schema: 'MultisigDAO';
  /** Authorized signers */
  signers: Address[];
  /** Required signature threshold */
  threshold: number;
  /** Current signatures on pending proposal */
  signatures: Record<string, string>; // address -> timestamp
  /** Executed actions history */
  actions: Array<{
    id: string;
    type: string;
    payload: JsonLogicValue;
    signatures: Record<string, string>;
    executedAt: string;
  }>;
}

/**
 * Token DAO state.
 */
export interface TokenDAOState extends Omit<DAOState, 'delegations'> {
  schema: 'TokenDAO';
  /** Token fiber ID */
  tokenId: string;
  /** Current balances */
  balances: Record<string, number>;
  /** Token delegation mapping (simple address -> address) */
  delegations: Record<string, string>;
  /** Minimum tokens to propose */
  proposalThreshold: number;
  /** Minimum total participation */
  quorum: number;
}

// ---------------------------------------------------------------------------
// State Machine Definitions (imported from JSON)
// ---------------------------------------------------------------------------

// Import governance state machine definitions
// Using standard imports (resolveJsonModule enabled in tsconfig)
import daoMultisigDef from './governance/dao-multisig.json';
import daoSingleDef from './governance/dao-single.json';
import daoThresholdDef from './governance/dao-threshold.json';
import daoTokenDef from './governance/dao-token.json';
import govConstitutionDef from './governance/governance-constitution.json';
import govExecutiveDef from './governance/governance-executive.json';
import govJudiciaryDef from './governance/governance-judiciary.json';
import govLegislatureDef from './governance/governance-legislature.json';
import govSimpleDef from './governance/governance-simple.json';

/**
 * DAO state machine definitions by type.
 */
export const DAO_DEFINITIONS: Record<DAOType, unknown> = {
  Single: daoSingleDef,
  Multisig: daoMultisigDef,
  Threshold: daoThresholdDef,
  Token: daoTokenDef,
};

/**
 * Governance state machine definitions by type.
 */
export const GOVERNANCE_DEFINITIONS: Record<GovernanceType, unknown> = {
  Legislature: govLegislatureDef,
  Executive: govExecutiveDef,
  Judiciary: govJudiciaryDef,
  Constitution: govConstitutionDef,
  Simple: govSimpleDef,
};

/**
 * Get the state machine definition for a DAO type.
 */
export function getDAODefinition(daoType: DAOType): unknown {
  const def = DAO_DEFINITIONS[daoType];
  if (!def) {
    throw new Error(`Unknown DAO type: ${daoType}`);
  }
  return def;
}

/**
 * Get the state machine definition for a governance type.
 */
export function getGovernanceDefinition(governanceType: GovernanceType): unknown {
  const def = GOVERNANCE_DEFINITIONS[governanceType];
  if (!def) {
    throw new Error(`Unknown governance type: ${governanceType}`);
  }
  return def;
}

/**
 * Extract state machine definition from JSON governance file.
 * Returns just the states, initialState, and transitions needed for CreateStateMachine.
 */
export function extractStateMachineDefinition(jsonDef: unknown): StateMachineDefinition {
  const def = jsonDef as {
    states: Record<string, unknown>;
    initialState: StateId;
    transitions: unknown[];
    metadata?: JsonLogicValue;
  };

  return {
    states: def.states,
    initialState: def.initialState,
    transitions: def.transitions,
    metadata: def.metadata,
  };
}
