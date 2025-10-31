# Workchain E2E Test Examples

This directory contains example-based test resources for the Workchain e2e test suite. Each example is self-contained with all related files grouped together in a dedicated directory.

## Directory Structure

Each example directory contains:
- `definition.json` - The state machine or oracle definition
- `initial-data.json` - Initial data for state machines
- `event-*.json` - Event files for state machine transitions
- `args-*.json` - Argument files for oracle method invocations
- `example.json` - Metadata describing the example and its test flows

## Available Examples

### State Machine Examples

#### approval-workflow
A simple approval workflow state machine with draft, submitted, approved, and rejected states.

**Files:**
- `definition.json` - State machine definition
- `initial-data.json` - Sample approval request
- `event-submit.json` - Submit event (draft → submitted)
- `event-approve.json` - Approve event (submitted → approved)
- `event-reject.json` - Reject event (submitted → rejected)

**Usage:**
```bash
# Create a state machine
node terminal.js sm create --definition examples/approval-workflow/definition.json --initialData examples/approval-workflow/initial-data.json

# Process submit event (use the CID from previous command)
node terminal.js sm process-event --address <CID> --event examples/approval-workflow/event-submit.json --expectedState submitted

# Process approve event
node terminal.js sm process-event --address <CID> --event examples/approval-workflow/event-approve.json --expectedState approved
```

#### simple-order
A basic order fulfillment state machine from pending to delivered.

**Files:**
- `definition.json` - State machine definition
- `initial-data.json` - Sample order
- `event-confirm.json` - Confirm event (pending → confirmed)
- `event-ship.json` - Ship event (confirmed → shipped)
- `event-deliver.json` - Deliver event (shipped → delivered)
- `event-cancel.json` - Cancel event (pending → cancelled)

**Usage:**
```bash
# Create and process full order flow
node terminal.js sm create --definition examples/simple-order/definition.json --initialData examples/simple-order/initial-data.json
node terminal.js sm process-event --address <CID> --event examples/simple-order/event-confirm.json --expectedState confirmed
node terminal.js sm process-event --address <CID> --event examples/simple-order/event-ship.json --expectedState shipped
node terminal.js sm process-event --address <CID> --event examples/simple-order/event-deliver.json --expectedState delivered
```

### Oracle Examples

#### counter-oracle
A stateful script oracle that maintains a counter with increment, decrement, and reset operations.

**Files:**
- `definition.json` - Oracle definition

**Usage:**
```bash
# Create oracle
node terminal.js or create --oracle examples/counter-oracle/definition.json

# Invoke methods (use the CID from previous command)
node terminal.js or invoke --address <CID> --method increment
node terminal.js or invoke --address <CID> --method increment
node terminal.js or invoke --address <CID> --method decrement
node terminal.js or invoke --address <CID> --method reset
```

#### calculator-oracle
A stateless script oracle for basic arithmetic operations.

**Files:**
- `definition.json` - Oracle definition
- `args-add.json` - Arguments for add method
- `args-subtract.json` - Arguments for subtract method
- `args-multiply.json` - Arguments for multiply method
- `args-divide.json` - Arguments for divide method

**Usage:**
```bash
# Create oracle
node terminal.js or create --oracle examples/calculator-oracle/definition.json

# Invoke methods with arguments (use the CID from previous command)
node terminal.js or invoke --address <CID> --method add --args examples/calculator-oracle/args-add.json
node terminal.js or invoke --address <CID> --method subtract --args examples/calculator-oracle/args-subtract.json
node terminal.js or invoke --address <CID> --method multiply --args examples/calculator-oracle/args-multiply.json
node terminal.js or invoke --address <CID> --method divide --args examples/calculator-oracle/args-divide.json
```

## Interactive Mode

Use interactive mode for guided selection of examples:

```bash
node terminal.js interactive
```

The interactive mode will:
1. Show you available examples by category
2. Let you select which example to use
3. Automatically use the correct files from the example directory

## Listing Examples

```bash
# List all examples
node terminal.js list

# List only state machine examples
node terminal.js list --type state-machines

# List only oracle examples
node terminal.js list --type oracles

# Get detailed info about a specific example
node terminal.js list --example approval-workflow
```

## Creating New Examples

To create a new example:

1. Create a directory: `examples/my-example/`
2. Add the required files based on the type:
   - State machines: `definition.json`, `initial-data.json`, `event-*.json`
   - Oracles: `definition.json`, `args-*.json` (if methods take arguments)
3. Create an `example.json` manifest:

```json
{
  "name": "My Example",
  "description": "Description of what this example demonstrates",
  "type": "state-machine",  // or "oracle"
  "definition": "definition.json",
  "initialData": "initial-data.json",  // for state machines
  "events": [  // for state machines
    {
      "name": "event-name",
      "description": "What this event does",
      "file": "event-name.json",
      "from": "source-state",
      "to": "target-state"
    }
  ],
  "methods": [  // for oracles
    {
      "name": "method-name",
      "description": "What this method does",
      "args": "args-method.json"  // or null
    }
  ],
  "testFlows": [
    {
      "name": "Flow name",
      "description": "Description of the test flow",
      "steps": [
        {"action": "create", "definition": "definition.json", "initialData": "initial-data.json"},
        {"action": "processEvent", "event": "event-name.json", "expectedState": "target-state"}
      ]
    }
  ]
}
```

## Key Benefits

This example-based structure provides:

1. **Clarity** - All related files are grouped together
2. **Discoverability** - Easy to see what examples exist and what they contain
3. **Documentation** - `example.json` serves as self-documenting metadata
4. **Reusability** - Examples can be easily copied and modified
5. **Testing** - `testFlows` define expected behavior for automated testing