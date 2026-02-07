# Trust Graph

> Composable coordination with reputation-gated actions.

**[Read the full architecture →](./ARCHITECTURE.md)**

## Quick Start

```typescript
import { governance, StateMachine } from '@ottochain/sdk';

// Create a market with multisig resolution
const market = await sdk.sm.create('coordination/market', {
  type: 'prediction',
  question: 'Will X happen by Y date?',
  governance: {
    strategy: 'multisig',
    signers: ['oracle1', 'oracle2', 'oracle3'],
    threshold: 2
  }
});

// Create a 2-of-3 treasury
const treasury = await sdk.sm.create('strategies/multisig', {
  name: 'Team Treasury',
  signers: ['alice', 'bob', 'carol'],
  threshold: 2
});
```

## Core Concepts

### Three Layers

| Layer | Purpose | Examples |
|-------|---------|----------|
| **Primitives** | Substrate | Identity, Reputation, Treasury |
| **Agreements** | Commitments | Contract, Escrow, Subscription |
| **Coordination** | Multi-agent | Market, Oracle, Governance |

### Governance as Strategy

Governance isn't a domain — it's a pluggable strategy:

| Strategy | Requirement | Use Case |
|----------|-------------|----------|
| `single` | 1 owner | Personal wallets |
| `multisig` | N-of-M signatures | Team treasuries |
| `token` | Token-weighted vote | Large DAOs |
| `threshold` | Reputation minimum | Merit-based orgs |

Any state machine can adopt any strategy.

### Reputation

Six weighted dimensions:

- **Reliability** (25%) — contract completion
- **Accuracy** (20%) — oracle correctness
- **Judgment** (15%) — dispute rulings
- **Participation** (15%) — governance activity
- **Social** (15%) — vouches received
- **Longevity** (10%) — time in ecosystem

Tiers: Newcomer → Participant → Contributor → Trusted → Elder

## Directory

```
trust-graph/
├── ARCHITECTURE.md      # Full vision
├── README.md            # This file
│
├── primitives/          # Substrate layer
│   └── reputation.json
│
├── strategies/          # Governance strategies
│   ├── single.json      # 1-of-1
│   ├── multisig.json    # N-of-M
│   ├── token.json       # Token-weighted
│   └── threshold.json   # Reputation-gated
│
├── coordination/        # Multi-agent coordination
│   ├── market.json      # Prediction, auction, crowdfund
│   ├── oracle.json      # Resolution, attestation
│   └── governance/      # Full org governance
│       ├── simple.json
│       ├── constitution.json
│       ├── legislature.json
│       ├── executive.json
│       └── judiciary.json
│
├── agreements/          # Bilateral/multilateral
│   └── escrow.json
│
├── guards/              # Access control
│   └── eligibility.json
│
└── events/              # Cross-SM propagation
    └── propagation.json
```

## Bridge API

```
POST /sm/create          Create any state machine
POST /sm/transition      Trigger transition
GET  /sm/:id             Current state
GET  /sm/:id/history     Transition history
```

## Related

- [ARCHITECTURE.md](./ARCHITECTURE.md) — Full system design
- [ottochain-sdk](https://github.com/ottobot-ai/ottochain-sdk) — TypeScript types & utilities
