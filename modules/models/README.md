# ottochain-models — DEPRECATED

> ⚠️ **This module is deprecated.** The types defined here are being migrated to the
> `ottochain-proto` module (ScalaPB-generated types from `modules/proto`).
>
> **Do not add new types here.** Use `ottochain.v1.*` proto-generated types instead.

## Migration Status

| Type | Status | Replacement |
|------|--------|-------------|
| `FiberStatus` | 🔄 Adapters in `ProtoAdapters` | `ottochain.v1.FiberStatus` |
| `StateId` | 🔄 Adapters in `ProtoAdapters` | `ottochain.v1.StateId` |
| `FiberOrdinal` | ⏳ Pending (uses `NonNegLong`) | `ottochain.v1.FiberOrdinal` |
| `EmittedEvent` | 🔄 Adapters planned | `ottochain.v1.EmittedEvent` |
| `AccessControlPolicy` | 🔄 Adapters planned | `ottochain.v1.AccessControlPolicy` |
| `StateMachineDefinition` | ⏳ Pending (complex schema) | `ottochain.v1.StateMachineDefinition` |
| `Records.StateMachineFiberRecord` | 🔄 Adapters in `ProtoAdapters` | `ottochain.v1.StateMachineFiberRecord` |
| `Records.ScriptFiberRecord` | 🔄 Adapters in `ProtoAdapters` | `ottochain.v1.ScriptFiberRecord` |
| `CalculatedState` | ⏳ Pending (extends `DataCalculatedState`) | `ottochain.v1.CalculatedState` |
| `OnChain` | ⏳ Pending (extends `DataOnChainState`) | `ottochain.v1.OnChainState` |
| `Updates.*` | ⏳ Pending (extends `DataUpdate`) | `ottochain.v1.*` messages |

## Why the delay?

Some types interface directly with Constellation's `DataCalculatedState`, `DataOnChainState`,
and `DataUpdate` traits. Replacing these requires Constellation framework changes or adapters
that are non-trivial and would be breaking changes to the metagraph lifecycle.

The fiber engine types (FiberInput, FiberContext, ExecutionState, etc.) are runtime-only and
have no proto equivalents by design — they stay as Scala types.

## Key PR

See: `feat/fiber-engine-module` — Initial extraction of fiber engine to separate publishable
module with proto dependency declared.
