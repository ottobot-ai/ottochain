# Governance Domain

State machine definitions for decentralized governance on OttoChain.

## Overview

The governance domain provides building blocks for decentralized decision-making, from simple DAOs to complex multi-branch constitutional systems.

## DAO State Machines

### Token DAO (`dao-token.json`)
Token-weighted voting governance with delegation support.
- **States**: ACTIVE → VOTING → QUEUED → (back to ACTIVE or DISSOLVED)
- **Features**: Token delegation, proposal thresholds, timelock, quorum requirements

### Multisig DAO (`dao-multisig.json`)
N-of-M signature threshold governance.
- **States**: ACTIVE ↔ PENDING → (back to ACTIVE or DISSOLVED)
- **Features**: Configurable threshold, proposal TTL, signer management

### Threshold DAO (`dao-threshold.json`)
Reputation-based threshold governance.
- **States**: ACTIVE ↔ VOTING → (back to ACTIVE or DISSOLVED)
- **Features**: Member/vote/propose thresholds, open membership

### Single DAO (`dao-single.json`)
Simple majority voting DAO.
- **States**: ACTIVE ↔ VOTING → (back to ACTIVE or DISSOLVED)
- **Features**: One-member-one-vote, simple quorum

## Constitutional Governance

For complex organizations requiring separation of powers:

### Legislature (`governance-legislature.json`)
Bill creation, committee review, floor voting.
- **States**: DRAFT → COMMITTEE → FLOOR → PASSED/FAILED/VETOED

### Executive (`governance-executive.json`)
Appointments, orders, veto power.
- **States**: ACTIVE with appointment and veto workflows

### Judiciary (`governance-judiciary.json`)
Case filing, hearings, appeals, constitutional review.
- **States**: FILED → HEARING → DECIDED → (APPEALED) → FINAL

### Constitution (`governance-constitution.json`)
Amendment process with ratification requirements.
- **States**: PROPOSED → DELIBERATION → RATIFICATION → RATIFIED/REJECTED

### Simple Governance (`governance-simple.json`)
Basic member management with rule changes and disputes.
- **States**: ACTIVE ↔ VOTING ↔ DISPUTE → (back to ACTIVE or DISSOLVED)

## SDK Integration

These state machines are implemented in the OttoChain SDK via protobuf definitions. See [ottochain-sdk](https://github.com/ottobot-ai/ottochain-sdk) for TypeScript types and client libraries.

## Usage

State machines define valid transitions. The OttoChain metagraph validates all governance actions against these definitions, ensuring:
- Only valid state transitions occur
- Required thresholds are met before execution
- Timelocks and deadlines are enforced
- Audit trail of all governance actions
