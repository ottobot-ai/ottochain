# Trust Graph Architecture

> A composable coordination system where reputation gates all actions.

## Vision

OttoChain is a **coordination substrate** — a system for agents (human or AI) to make agreements, resolve disputes, and build reputation. The Trust Graph is its core: a layered architecture where every action flows through reputation, and any state machine can compose with any governance strategy.

The key insight: **governance is not a domain, it's a strategy**. Markets, oracles, contracts — they all need governance. Rather than building governance into each, we make governance a pluggable layer that any state machine can adopt.

## The Three Layers

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│                           COORDINATION LAYER                                │
│                                                                             │
│    State machines that coordinate multi-agent activity                      │
│                                                                             │
│    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐                   │
│    │   MARKET    │    │   ORACLE    │    │  REGISTRY   │                   │
│    │             │    │             │    │             │                   │
│    │ • Predict   │    │ • Attest    │    │ • Register  │                   │
│    │ • Auction   │    │ • Resolve   │    │ • Verify    │                   │
│    │ • Crowdfund │    │ • Challenge │    │ • Revoke    │                   │
│    │ • Exchange  │    │ • Aggregate │    │ • Transfer  │                   │
│    └──────┬──────┘    └──────┬──────┘    └──────┬──────┘                   │
│           │                  │                  │                          │
│           │    ┌─────────────┴─────────────┐    │                          │
│           │    │                           │    │                          │
│           ▼    ▼                           ▼    ▼                          │
│    ┌──────────────────────────────────────────────────────┐                │
│    │              GOVERNANCE STRATEGIES                    │                │
│    │                                                       │                │
│    │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐        │                │
│    │  │ Single │ │Multisig│ │ Token  │ │Thresh- │        │                │
│    │  │        │ │        │ │Weighted│ │  old   │        │                │
│    │  │  1-of-1│ │ N-of-M │ │ voting │ │  rep   │        │                │
│    │  └────────┘ └────────┘ └────────┘ └────────┘        │                │
│    │                                                       │                │
│    │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐        │                │
│    │  │Delegate│ │Timelock│ │ Veto   │ │Optimis-│        │                │
│    │  │        │ │        │ │        │ │  tic   │        │                │
│    │  │ proxy  │ │ delay  │ │guardian│ │approval│        │                │
│    │  └────────┘ └────────┘ └────────┘ └────────┘        │                │
│    └──────────────────────────────────────────────────────┘                │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│                            AGREEMENT LAYER                                  │
│                                                                             │
│    Bilateral and multilateral commitments between agents                    │
│                                                                             │
│    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐                   │
│    │  CONTRACT   │    │   ESCROW    │    │     SLA     │                   │
│    │             │    │             │    │             │                   │
│    │ • Terms     │    │ • Custody   │    │ • Metrics   │                   │
│    │ • Milestones│    │ • Release   │    │ • Penalties │                   │
│    │ • Disputes  │    │ • Refund    │    │ • Breaches  │                   │
│    └─────────────┘    └─────────────┘    └─────────────┘                   │
│                                                                             │
│    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐                   │
│    │SUBSCRIPTION │    │   VESTING   │    │    BOND     │                   │
│    │             │    │             │    │             │                   │
│    │ • Recurring │    │ • Cliff     │    │ • Stake     │                   │
│    │ • Tiers     │    │ • Linear    │    │ • Slash     │                   │
│    │ • Cancel    │    │ • Milestone │    │ • Release   │                   │
│    └─────────────┘    └─────────────┘    └─────────────┘                   │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│                            PRIMITIVE LAYER                                  │
│                                                                             │
│    The substrate everything builds on                                       │
│                                                                             │
│    ┌───────────────────┐ ┌───────────────────┐ ┌───────────────────┐       │
│    │     IDENTITY      │ │    REPUTATION     │ │     TREASURY      │       │
│    │                   │ │                   │ │                   │       │
│    │ • Agent lifecycle │ │ • 6 dimensions    │ │ • Fund custody    │       │
│    │ • Vouching        │ │ • Decay/recovery  │ │ • Disbursement    │       │
│    │ • Attestations    │ │ • Tier progression│ │ • Accounting      │       │
│    │ • Challenges      │ │ • Slashing        │ │ • Auditing        │       │
│    └───────────────────┘ └───────────────────┘ └───────────────────┘       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Composability Model

### Governance as Strategy

Any state machine can adopt any governance strategy. The strategy determines *who can act* and *what's required*:

```typescript
// A prediction market with multisig resolution
const market = StateMachine.create({
  type: 'Market',
  variant: 'prediction',
  governance: {
    strategy: 'multisig',
    config: {
      signers: ['oracle1', 'oracle2', 'oracle3'],
      threshold: 2
    }
  }
});

// An oracle pool with token-weighted voting
const oracle = StateMachine.create({
  type: 'Oracle',
  domain: 'price_feeds',
  governance: {
    strategy: 'token',
    config: {
      tokenId: 'DAG...',
      quorum: 10000,
      proposalThreshold: 1000
    }
  }
});

// A contract with reputation threshold for disputes
const contract = StateMachine.create({
  type: 'Contract',
  parties: ['alice', 'bob'],
  governance: {
    strategy: 'threshold',
    config: {
      disputeThreshold: 60,  // Need 60+ rep to file dispute
      judgeThreshold: 80     // Need 80+ rep to judge
    }
  }
});
```

### Strategy Composition

Strategies can compose:

```typescript
// Optimistic + Timelock: auto-approve after delay unless vetoed
governance: {
  strategy: 'optimistic',
  config: {
    timelockMs: 86400000,  // 24 hours
    vetoThreshold: 3       // 3 vetoes to block
  }
}

// Multisig + Delegation: signers can delegate
governance: {
  strategy: 'multisig',
  config: {
    threshold: 2,
    signers: ['alice', 'bob', 'carol'],
    allowDelegation: true
  }
}

// Token + Timelock: vote, then wait
governance: {
  strategy: 'token',
  config: {
    quorum: 10000,
    timelockMs: 172800000  // 48 hours after vote passes
  }
}
```

### Cross-Layer References

State machines reference each other through fiber IDs:

```
Market (fiber: abc123)
  ├── governance.strategy: 'threshold'
  ├── governance.reputationSource: Identity (fiber: def456)
  ├── escrow: Escrow (fiber: ghi789)
  ├── oracle: Oracle (fiber: jkl012)
  └── treasury: Treasury (fiber: mno345)
```

When Market resolves:
1. Query `reputationSource` for oracle's current reputation
2. If above threshold, accept resolution
3. Release funds from `escrow` to winners
4. Send fees to `treasury`
5. Update oracle's reputation via cross-SM event

## Reputation System

### Six Dimensions

Reputation isn't a single number — it's a weighted composite:

| Dimension | Weight | Source |
|-----------|--------|--------|
| **Reliability** | 25% | Contract completion rate |
| **Accuracy** | 20% | Oracle correctness |
| **Judgment** | 15% | Dispute ruling quality |
| **Participation** | 15% | Governance activity |
| **Social** | 15% | Vouches received |
| **Longevity** | 10% | Time in ecosystem |

### Tier Progression

```
NEWCOMER      0-20   Basic participation
PARTICIPANT  20-40   Can vote, create markets
CONTRIBUTOR  40-60   Can propose, serve as oracle, vouch others
TRUSTED      60-80   Can judge disputes, high-value operations
ELDER        80-100  Veto power, constitutional amendments
```

### Dynamics

**Decay**: Reputation halves every 30 days of inactivity. This prevents dormant accounts from holding power.

**Slashing**: Misbehavior triggers immediate reputation loss:
- Oracle wrong: -30 accuracy
- Contract breach: -40 reliability
- Malicious ruling: -50 judgment

**Recovery**: After a 7-day probation:
- +5 reputation per day of good behavior
- Capped at 80% of pre-slash level
- Full recovery requires sustained positive actions

### Eligibility Guards

Roles map to reputation requirements:

```json
{
  "voter": { "composite": 20 },
  "proposer": { "composite": 40, "participation": 30 },
  "oracle": { "composite": 40, "accuracy": 50 },
  "judge": { "composite": 60, "judgment": 60 },
  "elder": { "composite": 80, "longevity": 70 }
}
```

Guards are checked at transition time. If an agent's reputation drops below threshold, they lose eligibility until they recover.

## Event Propagation

Actions in one SM affect others through cross-SM events:

```
┌─────────────────┐
│  Source Event   │
│                 │
│  Oracle slashed │
│  in Market SM   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│  Identity SM    │     │  Reputation     │
│                 │     │                 │
│  Add flag:      │     │  accuracy: -30  │
│  "slashed"      │     │  recalculate    │
│  expires: 7d    │     │  composite      │
└────────┬────────┘     └────────┬────────┘
         │                       │
         ▼                       ▼
┌─────────────────────────────────────────┐
│          Eligibility Check              │
│                                         │
│  Oracle now below threshold for:        │
│  - High-value markets (blocked)         │
│  - Judge role (blocked)                 │
│  - Normal oracle (blocked during flag)  │
└─────────────────────────────────────────┘
```

### Propagation Rules

Defined in `cross-sm-events.json`:

| Source | Event | Targets | Effect |
|--------|-------|---------|--------|
| Market | `oracle_slashed` | Identity, Reputation | Flag + accuracy drop |
| Market | `resolved_correctly` | Reputation | Accuracy boost |
| Contract | `completed` | Reputation | Reliability boost (both parties) |
| Contract | `breached` | Identity, Reputation | Flag + reliability drop |
| Governance | `proposal_passed` | Reputation | Participation boost |
| Governance | `vote_cast` | Reputation | Participation micro-boost |
| Oracle | `attestation_verified` | Reputation | Accuracy boost |
| Identity | `vouch_given` | Reputation | Social boost (receiver) |
| Judiciary | `ruling_upheld` | Reputation | Judgment boost |
| Judiciary | `ruling_overturned` | Reputation | Judgment drop |

## Implementation

### State Machine Structure

All state machines follow the same JSON Logic format:

```json
{
  "metadata": {
    "name": "MarketV1",
    "version": "1.0.0",
    "category": "coordination/market"
  },
  "states": { ... },
  "initialState": { "value": "PROPOSED" },
  "transitions": [
    {
      "from": { "value": "PROPOSED" },
      "to": { "value": "OPEN" },
      "eventName": "activate",
      "guard": { ... },      // JSON Logic condition
      "effect": { ... },     // JSON Logic state update
      "emits": [ ... ]       // Cross-SM events
    }
  ],
  "governance": {
    "strategy": "threshold",
    "config": { ... }
  },
  "crossReferences": {
    "Identity": "agent verification",
    "Reputation": "eligibility checks",
    "Escrow": "fund custody"
  }
}
```

### Combiner Integration

The Combiner (L0 consensus layer) handles:

1. **Transition validation**: Check guards, including governance requirements
2. **Effect application**: Apply state changes atomically
3. **Event propagation**: Process cross-SM events after each block
4. **Reputation updates**: Batch reputation changes efficiently

### Bridge Routes

Generic routes for all state machines:

```
POST /sm/create          Create any state machine
POST /sm/transition      Transition any state machine
GET  /sm/:id             Get current state
GET  /sm/:id/history     Get transition history
GET  /sm/:id/governance  Get governance config
POST /sm/:id/governance  Update governance (if allowed)
```

## Design Principles

### 1. Composition Over Inheritance

State machines don't inherit from each other. They compose by referencing other SMs and adopting governance strategies.

### 2. Reputation as Universal Currency

Every action affects reputation. Reputation gates every role. This creates accountability without central authority.

### 3. Eventual Consistency

Cross-SM events propagate asynchronously within the same block. The system is eventually consistent at block boundaries.

### 4. Progressive Decentralization

Start with simple governance (single owner), graduate to complex (token voting) as the system matures.

### 5. Defense in Depth

Multiple layers of protection:
- Eligibility guards (can you act?)
- Governance strategies (is there consensus?)
- Timelocks (can it be vetoed?)
- Reputation slashing (are there consequences?)

## File Organization

```
docs/trust-graph/
├── ARCHITECTURE.md           # This document
├── README.md                 # Quick reference
│
├── primitives/
│   ├── identity.json         # Agent lifecycle
│   ├── reputation.json       # Composite scoring
│   └── treasury.json         # Fund management
│
├── strategies/
│   ├── single.json           # 1-of-1
│   ├── multisig.json         # N-of-M
│   ├── token.json            # Token-weighted
│   ├── threshold.json        # Reputation-gated
│   ├── delegation.json       # Proxy voting
│   ├── timelock.json         # Delayed execution
│   ├── optimistic.json       # Auto-approve unless vetoed
│   └── veto.json             # Guardian override
│
├── coordination/
│   ├── market.json           # Prediction, auction, crowdfund
│   ├── oracle.json           # Resolution, attestation
│   └── registry.json         # Registration, verification
│
├── agreements/
│   ├── contract.json         # Bilateral/multilateral
│   ├── escrow.json           # Fund custody
│   ├── subscription.json     # Recurring payments
│   ├── vesting.json          # Token release schedules
│   ├── bond.json             # Stake with slashing
│   └── sla.json              # Service guarantees
│
├── guards/
│   ├── eligibility.json      # Role → reputation requirements
│   └── rate-limits.json      # Action frequency limits
│
└── events/
    └── propagation.json      # Cross-SM event rules
```

---

*This architecture enables "gobs of state machines cross intersecting" — an intricate web of coordination primitives that compose freely while reputation maintains accountability.*
