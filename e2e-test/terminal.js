require('dotenv').config();

const { Command } = require('commander');
const crypto = require("crypto");
const axios = require('axios');
const generateProof = require("./lib/generateProof.js");
const generateWallet = require("./lib/generateWallet.js");
const sendDataTransactionsUsingUrls = require("./lib/sendDataTransaction.js");
const getMetagraphEnv = require("./lib/metagraphEnv.js");

const program = new Command()
  .name('workchain-terminal')
  .description('Interactive CLI for testing Workchain state machines and script oracles')
  .option('--address <address>', 'Choose a fixed UUID for the resource. Defaults to random UUID if not provided')
  .option('--target <target>', 'Target environment: [local, remote, ci]. Default: "local"', "local")
  .option('--mode <mode>', "Operation mode: ['send', 'validate', 'send+validate'] (default: 'send+validate')", 'send+validate')
  .option('--waitTime <N>', 'Seconds to wait after sending before validation. Default: 5', '5')
  .option('--retryDelay <N>', 'Seconds between validation retries. Default: 3', '3')
  .option('--maxRetries <N>', 'Max validation retry attempts. Default: 5', '5')
  .option('--wallets <wallets>', 'Comma-separated list of signers [alice, bob, charlie, diane, james]. Default: alice', 'alice');

const exitOnError = (contextMessage, err) => {
  console.error(contextMessage, err);
  process.exit(1);
};

const sendDataTransaction = async (message, wallets, env) => {
  try {
    const { node1DataL1, node2DataL1, node3DataL1 } = env;

    const proofs = await Promise.all(
      Object.values(wallets).map(async ({ account, privateKey }) => {
        return await generateProof(message, privateKey, account);
      })
    );

    const signedUpdate = {
      value: { ...message },
      proofs: proofs,
    };

    console.log(
      `\x1b[33m[sendDataTransaction]\x1b[36m Client request:\x1b[0m ${JSON.stringify(signedUpdate, null, 2)}`
    );

    const responses = await Promise.allSettled([
      sendDataTransactionsUsingUrls(signedUpdate, node1DataL1),
      sendDataTransactionsUsingUrls(signedUpdate, node2DataL1),
      sendDataTransactionsUsingUrls(signedUpdate, node3DataL1)
    ]);

    const fulfilledResponses = responses.filter(result => result.status === "fulfilled");

    if (fulfilledResponses.length > 0) {
      console.log(`\x1b[33m[sendDataTransaction]\x1b[32m Successful responses from ${fulfilledResponses.length} nodes\x1b[0m`);
      return fulfilledResponses.map(result => result.value);
    } else {
      const errorMessages = responses
        .map(result => (result.status === "rejected" ? result.reason.message : ""))
        .join("; ");
      throw new Error(`All requests failed. Errors: ${errorMessages}`);
    }
  } catch (err) {
    return Promise.reject(new Error(`\x1b[31m[sendDataTransaction]\x1b[0m Encountered error: ${err.message}`));
  }
};

async function getApplicationState(url) {
  try {
    const res = await axios.get(url);
    return res.data;
  } catch (err) {
    throw new Error(`Failed to fetch state from ${url}: ${err.message}`);
  }
}

async function getInitialStates(urls) {
  const initialStates = await Promise.all(urls.map((url) => getApplicationState(url)));

  const statesMap = {};
  for (let i = 0; i < urls.length; i++) {
    statesMap[urls[i]] = {
      initial: initialStates[i],
      final: {}
    };
  }

  return statesMap;
}

async function updateFinalStates(statesMap) {
  const urls = Object.keys(statesMap);
  const finalStates = await Promise.all(urls.map((url) => getApplicationState(url)));

  for (let i = 0; i < urls.length; i++) {
    statesMap[urls[i]].final = finalStates[i];
  }

  return statesMap;
}

const validate = async (
  txValidation,
  cid,
  statesMap,
  options,
  wallets,
  maxRetries,
  retryDelayMs
) => {
  const validateWithRetries = async (currentAttempt, statesMap) => {
    console.error(`\x1b[33m[validate]\x1b[0m Initiating attempt (${currentAttempt}/${maxRetries})...`);
    try {
      await txValidation({ cid, statesMap, options, wallets });
    } catch (err) {
      if (currentAttempt >= maxRetries) {
        console.error(`\x1b[31m[validate]\x1b[0m Max retries (${maxRetries}) reached. Last error: ${err.message}`);
        throw err;
      } else {
        console.error(`\x1b[31m[validate]\x1b[0m Attempt ${currentAttempt} failed (${currentAttempt}/${maxRetries})\n${err.message}\nRetrying in ${retryDelayMs}ms...`);

        await new Promise((resolve) => setTimeout(resolve, retryDelayMs));

        let updatedStates;
        try {
          updatedStates = await updateFinalStates(statesMap);
          console.log("\x1b[33m[validate]\x1b[0m Updated final states in map for next retry.");
        } catch (refreshErr) {
          console.error(`\x1b[31m[validate]\x1b[0m Unable to refresh final states: ${refreshErr.message}`);
          throw refreshErr;
        }

        return validateWithRetries(currentAttempt + 1, updatedStates);
      }
    }
  };

  const _statesMap = await updateFinalStates(statesMap);
  await validateWithRetries(1, _statesMap);
};

// State Machine Commands
const stateMachineCmd = new Command('state-machine')
  .alias('sm')
  .description('Manage state machine fibers');

stateMachineCmd
  .command('create')
  .description('Create a new state machine fiber')
  .requiredOption('--definition <path>', 'Path to state machine definition JSON file')
  .requiredOption('--initialData <path>', 'Path to initial data JSON file')
  .option('--parentFiberId <uuid>', 'Optional parent fiber ID')
  .action(async (cmdOptions) => {
    await executeCommand('state-machine', 'create', cmdOptions);
  });

stateMachineCmd
  .command('process-event')
  .alias('event')
  .description('Process an event on an existing state machine fiber')
  .requiredOption('--event <path>', 'Path to event JSON file')
  .option('--expectedState <state>', 'Expected resulting state (for validation)')
  .action(async (cmdOptions) => {
    await executeCommand('state-machine', 'processEvent', cmdOptions);
  });

stateMachineCmd
  .command('archive')
  .description('Archive a state machine fiber')
  .action(async (cmdOptions) => {
    await executeCommand('state-machine', 'archive', cmdOptions);
  });

// Oracle Commands
const oracleCmd = new Command('oracle')
  .alias('or')
  .description('Manage script oracles');

oracleCmd
  .command('create')
  .description('Create a new script oracle')
  .requiredOption('--oracle <path>', 'Path to oracle definition JSON file')
  .action(async (cmdOptions) => {
    await executeCommand('oracle', 'create', cmdOptions);
  });

oracleCmd
  .command('invoke')
  .description('Invoke a method on an existing script oracle')
  .requiredOption('--method <name>', 'Method name to invoke')
  .option('--args <path>', 'Path to arguments JSON file')
  .option('--expectedResult <json>', 'Expected result (for validation)')
  .action(async (cmdOptions) => {
    await executeCommand('oracle', 'invoke', cmdOptions);
  });

// Query Commands
const queryCmd = new Command('query')
  .alias('q')
  .description('Query metagraph state from ML0 endpoints');

queryCmd
  .command('checkpoint')
  .description('Get current checkpoint')
  .option('--node <number>', 'Node number (1, 2, or 3). Default: 1', '1')
  .action(async (cmdOptions) => {
    await executeQuery('checkpoint', cmdOptions);
  });

queryCmd
  .command('onchain')
  .description('Get onchain state')
  .option('--node <number>', 'Node number (1, 2, or 3). Default: 1', '1')
  .action(async (cmdOptions) => {
    await executeQuery('onchain', cmdOptions);
  });

queryCmd
  .command('state-machines')
  .alias('sm')
  .description('List all state machines')
  .option('--node <number>', 'Node number (1, 2, or 3). Default: 1', '1')
  .option('--status <status>', 'Filter by status: [Active, Archived]')
  .option('--fiberId <uuid>', 'Get specific fiber by ID')
  .option('--events', 'Get event log (requires --fiberId)')
  .action(async (cmdOptions) => {
    await executeQuery('state-machines', cmdOptions);
  });

queryCmd
  .command('oracles')
  .alias('or')
  .description('List all oracles')
  .option('--node <number>', 'Node number (1, 2, or 3). Default: 1', '1')
  .option('--status <status>', 'Filter by status: [Active, Archived]')
  .option('--oracleId <uuid>', 'Get specific oracle by ID')
  .option('--invocations', 'Get invocation log (requires --oracleId)')
  .action(async (cmdOptions) => {
    await executeQuery('oracles', cmdOptions);
  });

async function executeQuery(endpoint, cmdOptions) {
  const globalOpts = program.opts();
  const options = { ...globalOpts, ...cmdOptions };

  try {
    const { node1ML0, node2ML0, node3ML0 } = getMetagraphEnv(options.target);
    const nodeMap = { '1': node1ML0, '2': node2ML0, '3': node3ML0 };
    const baseUrl = nodeMap[options.node] || node1ML0;

    let url;
    switch (endpoint) {
      case 'checkpoint':
        url = `${baseUrl}/data-application/checkpoint`;
        break;
      case 'onchain':
        url = `${baseUrl}/data-application/onchain`;
        break;
      case 'state-machines':
        if (options.fiberId && options.events) {
          url = `${baseUrl}/data-application/state-machines/${options.fiberId}/events`;
        } else if (options.fiberId) {
          url = `${baseUrl}/data-application/state-machines/${options.fiberId}`;
        } else {
          url = `${baseUrl}/data-application/state-machines`;
          if (options.status) url += `?status=${options.status}`;
        }
        break;
      case 'oracles':
        if (options.oracleId && options.invocations) {
          url = `${baseUrl}/data-application/oracles/${options.oracleId}/invocations`;
        } else if (options.oracleId) {
          url = `${baseUrl}/data-application/oracles/${options.oracleId}`;
        } else {
          url = `${baseUrl}/data-application/oracles`;
          if (options.status) url += `?status=${options.status}`;
        }
        break;
    }

    console.log(`\x1b[33m[query]\x1b[36m Fetching from:\x1b[0m ${url}`);
    const response = await axios.get(url);
    console.log(JSON.stringify(response.data, null, 2));
    process.exit(0);
  } catch (error) {
    exitOnError(`\x1b[31m[query]\x1b[0m Query failed:`, error);
  }
}

// Helper Commands
program
  .command('list')
  .description('List available examples')
  .option('--type <type>', 'Filter by type: [state-machines, oracles, all]', 'all')
  .option('--example <name>', 'Show detailed info for a specific example')
  .action(async (cmdOptions) => {
    const fs = require('fs');
    const path = require('path');

    const examplesDir = path.join(__dirname, 'examples');

    if (cmdOptions.example) {
      const examplePath = path.join(examplesDir, cmdOptions.example, 'example.json');
      if (!fs.existsSync(examplePath)) {
        console.error(`\x1b[31mExample "${cmdOptions.example}" not found\x1b[0m`);
        process.exit(1);
      }

      const example = JSON.parse(fs.readFileSync(examplePath, 'utf8'));
      console.log(`\x1b[36m\n=== ${example.name} ===\x1b[0m`);
      console.log(`\x1b[33mType:\x1b[0m ${example.type}`);
      console.log(`\x1b[33mDescription:\x1b[0m ${example.description}\n`);

      console.log(`\x1b[33mFiles:\x1b[0m`);
      const files = fs.readdirSync(path.join(examplesDir, cmdOptions.example))
        .filter(f => f.endsWith('.json') && f !== 'example.json');
      files.forEach(f => console.log(`  - examples/${cmdOptions.example}/${f}`));

      if (example.events) {
        console.log(`\n\x1b[33mEvents:\x1b[0m`);
        example.events.forEach(e => console.log(`  - ${e.name}: ${e.description} (${e.from} → ${e.to})`));
      }

      if (example.methods) {
        console.log(`\n\x1b[33mMethods:\x1b[0m`);
        example.methods.forEach(m => console.log(`  - ${m.name}: ${m.description}`));
      }

      if (example.testFlows) {
        console.log(`\n\x1b[33mTest Flows:\x1b[0m`);
        example.testFlows.forEach(flow => {
          console.log(`  ${flow.name}: ${flow.description}`);
        });
      }

      console.log('\n');
      return;
    }

    console.log('\x1b[36m\n=== Available Examples ===\x1b[0m\n');

    const examples = fs.readdirSync(examplesDir)
      .filter(f => fs.statSync(path.join(examplesDir, f)).isDirectory())
      .map(dir => {
        const examplePath = path.join(examplesDir, dir, 'example.json');
        if (!fs.existsSync(examplePath)) return null;
        return { dir, ...JSON.parse(fs.readFileSync(examplePath, 'utf8')) };
      })
      .filter(e => e !== null);

    const stateMachines = examples.filter(e => e.type === 'state-machine');
    const oracles = examples.filter(e => e.type === 'oracle');

    if (cmdOptions.type === 'all' || cmdOptions.type === 'state-machines') {
      console.log('\x1b[33mState Machine Examples:\x1b[0m');
      stateMachines.forEach(e => {
        console.log(`  \x1b[36m${e.dir}\x1b[0m - ${e.name}`);
        console.log(`    ${e.description}`);
      });
      console.log('');
    }

    if (cmdOptions.type === 'all' || cmdOptions.type === 'oracles') {
      console.log('\x1b[33mOracle Examples:\x1b[0m');
      oracles.forEach(e => {
        console.log(`  \x1b[36m${e.dir}\x1b[0m - ${e.name}`);
        console.log(`    ${e.description}`);
      });
      console.log('');
    }

    console.log('Use \x1b[36m--example <name>\x1b[0m to see detailed information about an example\n');
  });

async function executeCommand(category, operation, cmdOptions) {
  const globalOpts = program.opts();
  const options = { ...globalOpts, ...cmdOptions };

  try {
    const { globalL0Url, node1ML0, node2ML0, node3ML0, node1DataL1, node2DataL1, node3DataL1 } = getMetagraphEnv(options.target);

    const cid = options.address || crypto.randomUUID();
    const wallets = [...new Set(options.wallets.split(','))].reduce((acc, user) => {
      acc[user] = generateWallet(globalL0Url, user);
      return acc;
    }, {});

    const operationFileMap = {
      'state-machine': {
        'create': 'createFiber',
        'processEvent': 'processEvent',
        'archive': 'archiveFiber'
      },
      'oracle': {
        'create': 'createOracle',
        'invoke': 'invokeOracle'
      }
    };

    const fileName = operationFileMap[category]?.[operation] || operation;
    const { generator, validator } = require(`./lib/${category}/${fileName}.js`);
    const message = generator({ cid, wallets, options });

    const maxRetries = parseInt(options.maxRetries);
    const retryDelayMs = parseInt(options.retryDelay) * 1000;
    const waitTimeMs = parseInt(options.waitTime) * 1000;

    const ml0Env = [node1ML0, node2ML0, node3ML0].map(a => a + `/data-application/checkpoint`);
    const dataEnv = { node1DataL1, node2DataL1, node3DataL1 };

    switch (options.mode) {
      case 'send': {
        await sendDataTransaction(message, wallets, dataEnv);
        console.log(`\x1b[32m\nTransaction sent successfully! CID: ${cid}\x1b[0m`);
        process.exit(0);
        break;
      }

      case "validate": {
        const statesMap = await getInitialStates(ml0Env);
        await validate(validator, cid, statesMap, options, wallets, maxRetries, retryDelayMs);
        console.log(`\x1b[32m\nValidation completed successfully! CID: ${cid}\x1b[0m`);
        process.exit(0);
        break;
      }

      case 'send+validate':
      default: {
        console.log("\x1b[33m[terminal]\x1b[0m Fetching initial states from ML0 endpoints...");
        const initialStates = await getInitialStates(ml0Env);

        console.log(`\x1b[33m[terminal]\x1b[0m Sending transaction with CID: ${cid}...`);
        await sendDataTransaction(message, wallets, dataEnv);

        console.log(`\x1b[33m[terminal]\x1b[0m Waiting ${options.waitTime} seconds before validation...`);
        await new Promise((resolve) => setTimeout(resolve, waitTimeMs));

        console.log(`\x1b[33m[terminal]\x1b[0m Starting validation...`);
        await validate(validator, cid, initialStates, options, wallets, maxRetries, retryDelayMs);

        console.log(`\x1b[32m\n✓ Operation completed successfully! CID: ${cid}\x1b[0m`);
        process.exit(0);
        break;
      }
    }
  } catch (error) {
    exitOnError(`\x1b[31m[terminal]\x1b[0m ${category}/${operation} failed:`, error);
  }
}

// Interactive Mode
program
  .command('interactive')
  .alias('i')
  .description('Interactive mode with guided prompts')
  .action(async () => {
    const readline = require('readline');
    const fs = require('fs');
    const path = require('path');

    const rl = readline.createInterface({
      input: process.stdin,
      output: process.stdout
    });

    const question = (query) => new Promise((resolve) => rl.question(query, resolve));

    try {
      console.log('\x1b[36m\n=== Workchain Interactive Terminal ===\x1b[0m\n');

      const action = await question('What would you like to do?\n  1) Create state machine\n  2) Process event on state machine\n  3) Archive state machine\n  4) Create oracle\n  5) Invoke oracle\n  6) Query state\n\nChoice (1-6): ');

      if (action === '6') {
        const queryType = await question('\nWhat would you like to query?\n  1) Checkpoint\n  2) Onchain state\n  3) All state machines\n  4) Specific state machine\n  5) All oracles\n  6) Specific oracle\n\nChoice (1-6): ');

        const node = await question('\nWhich node? (1, 2, or 3) [default: 1]: ') || '1';
        const target = await question('Environment? (local/remote/ci) [default: local]: ') || 'local';

        rl.close();

        const cmdOptions = { node, target };

        if (queryType === '1') {
          await executeQuery('checkpoint', cmdOptions);
        } else if (queryType === '2') {
          await executeQuery('onchain', cmdOptions);
        } else if (queryType === '3') {
          const status = await question('Filter by status? (Active/Archived) [press enter for all]: ');
          cmdOptions.status = status || undefined;
          await executeQuery('state-machines', cmdOptions);
        } else if (queryType === '4') {
          const fiberId = await question('Enter fiber ID: ');
          const showEvents = await question('Show event log? (y/n) [default: n]: ');
          cmdOptions.fiberId = fiberId;
          cmdOptions.events = showEvents.toLowerCase() === 'y';
          await executeQuery('state-machines', cmdOptions);
        } else if (queryType === '5') {
          const status = await question('Filter by status? (Active/Archived) [press enter for all]: ');
          cmdOptions.status = status || undefined;
          await executeQuery('oracles', cmdOptions);
        } else if (queryType === '6') {
          const oracleId = await question('Enter oracle ID: ');
          const showInvocations = await question('Show invocation log? (y/n) [default: n]: ');
          cmdOptions.oracleId = oracleId;
          cmdOptions.invocations = showInvocations.toLowerCase() === 'y';
          await executeQuery('oracles', cmdOptions);
        }
        return;
      }

      const listFiles = (dir, filter) => {
        if (!fs.existsSync(dir)) return [];
        return fs.readdirSync(dir)
          .filter(f => f.endsWith('.json') && (!filter || filter(f)))
          .map(f => path.basename(f));
      };

      let category, operation, cmdOptions = {};

      if (action === '1') {
        category = 'state-machine';
        operation = 'create';

        const examplesDir = path.join(__dirname, 'examples');
        const smExamples = fs.readdirSync(examplesDir)
          .filter(dir => {
            const examplePath = path.join(examplesDir, dir, 'example.json');
            if (!fs.existsSync(examplePath)) return false;
            const example = JSON.parse(fs.readFileSync(examplePath, 'utf8'));
            return example.type === 'state-machine';
          });

        console.log('\n\x1b[33mAvailable state machine examples:\x1b[0m');
        smExamples.forEach((dir, i) => {
          const example = JSON.parse(fs.readFileSync(path.join(examplesDir, dir, 'example.json'), 'utf8'));
          console.log(`  ${i + 1}) ${dir} - ${example.name}`);
        });

        const exampleChoice = await question('\nChoose example (number or directory name): ');
        const exampleDir = isNaN(exampleChoice) ? exampleChoice : smExamples[parseInt(exampleChoice) - 1];
        cmdOptions.definition = `examples/${exampleDir}/definition.json`;
        cmdOptions.initialData = `examples/${exampleDir}/initial-data.json`;

        const parentId = await question('\nParent fiber ID (optional, press enter to skip): ');
        if (parentId) cmdOptions.parentFiberId = parentId;

      } else if (action === '2') {
        category = 'state-machine';
        operation = 'processEvent';

        const fiberId = await question('\nEnter fiber ID (CID): ');
        cmdOptions.address = fiberId;

        const examplesDir = path.join(__dirname, 'examples');
        const smExamples = fs.readdirSync(examplesDir)
          .filter(dir => {
            const examplePath = path.join(examplesDir, dir, 'example.json');
            if (!fs.existsSync(examplePath)) return false;
            const example = JSON.parse(fs.readFileSync(examplePath, 'utf8'));
            return example.type === 'state-machine';
          });

        console.log('\n\x1b[33mSelect example to choose events from:\x1b[0m');
        smExamples.forEach((dir, i) => {
          const example = JSON.parse(fs.readFileSync(path.join(examplesDir, dir, 'example.json'), 'utf8'));
          console.log(`  ${i + 1}) ${dir} - ${example.name}`);
        });

        const exampleChoice = await question('\nChoose example (number or directory name): ');
        const exampleDir = isNaN(exampleChoice) ? exampleChoice : smExamples[parseInt(exampleChoice) - 1];

        const eventFiles = fs.readdirSync(path.join(examplesDir, exampleDir))
          .filter(f => f.startsWith('event-') && f.endsWith('.json'));

        console.log('\n\x1b[33mAvailable events:\x1b[0m');
        eventFiles.forEach((f, i) => console.log(`  ${i + 1}) ${f}`));

        const eventChoice = await question('\nChoose event (number or filename): ');
        const eventFile = isNaN(eventChoice) ? eventChoice : eventFiles[parseInt(eventChoice) - 1];
        cmdOptions.event = `examples/${exampleDir}/${eventFile}`;

        const expectedState = await question('\nExpected state after event (optional): ');
        if (expectedState) cmdOptions.expectedState = expectedState;

      } else if (action === '3') {
        category = 'state-machine';
        operation = 'archive';

        const fiberId = await question('\nEnter fiber ID to archive: ');
        cmdOptions.address = fiberId;

      } else if (action === '4') {
        category = 'oracle';
        operation = 'create';

        const examplesDir = path.join(__dirname, 'examples');
        const oracleExamples = fs.readdirSync(examplesDir)
          .filter(dir => {
            const examplePath = path.join(examplesDir, dir, 'example.json');
            if (!fs.existsSync(examplePath)) return false;
            const example = JSON.parse(fs.readFileSync(examplePath, 'utf8'));
            return example.type === 'oracle';
          });

        console.log('\n\x1b[33mAvailable oracle examples:\x1b[0m');
        oracleExamples.forEach((dir, i) => {
          const example = JSON.parse(fs.readFileSync(path.join(examplesDir, dir, 'example.json'), 'utf8'));
          console.log(`  ${i + 1}) ${dir} - ${example.name}`);
        });

        const exampleChoice = await question('\nChoose example (number or directory name): ');
        const exampleDir = isNaN(exampleChoice) ? exampleChoice : oracleExamples[parseInt(exampleChoice) - 1];
        cmdOptions.oracle = `examples/${exampleDir}/definition.json`;

      } else if (action === '5') {
        category = 'oracle';
        operation = 'invoke';

        const oracleId = await question('\nEnter oracle ID (CID): ');
        cmdOptions.address = oracleId;

        const examplesDir = path.join(__dirname, 'examples');
        const oracleExamples = fs.readdirSync(examplesDir)
          .filter(dir => {
            const examplePath = path.join(examplesDir, dir, 'example.json');
            if (!fs.existsSync(examplePath)) return false;
            const example = JSON.parse(fs.readFileSync(examplePath, 'utf8'));
            return example.type === 'oracle';
          });

        console.log('\n\x1b[33mSelect oracle example:\x1b[0m');
        oracleExamples.forEach((dir, i) => {
          const example = JSON.parse(fs.readFileSync(path.join(examplesDir, dir, 'example.json'), 'utf8'));
          console.log(`  ${i + 1}) ${dir} - ${example.name}`);
        });

        const exampleChoice = await question('\nChoose example (number or directory name): ');
        const exampleDir = isNaN(exampleChoice) ? exampleChoice : oracleExamples[parseInt(exampleChoice) - 1];

        const example = JSON.parse(fs.readFileSync(path.join(examplesDir, exampleDir, 'example.json'), 'utf8'));
        console.log('\n\x1b[33mAvailable methods:\x1b[0m');
        example.methods.forEach((m, i) => console.log(`  ${i + 1}) ${m.name} - ${m.description}`));

        const methodChoice = await question('\nChoose method (number or name): ');
        const method = isNaN(methodChoice) ? methodChoice : example.methods[parseInt(methodChoice) - 1].name;
        cmdOptions.method = method;

        const selectedMethod = example.methods.find(m => m.name === method);
        if (selectedMethod && selectedMethod.args) {
          cmdOptions.args = `examples/${exampleDir}/${selectedMethod.args}`;
        }

        const expectedResult = await question('\nExpected result (optional, JSON string): ');
        if (expectedResult) cmdOptions.expectedResult = expectedResult;
      }

      const target = await question('\nEnvironment? (local/remote/ci) [default: local]: ') || 'local';
      const mode = await question('Mode? (send/validate/send+validate) [default: send+validate]: ') || 'send+validate';
      const wallets = await question('Wallets (comma-separated: alice,bob,charlie,diane,james) [default: alice]: ') || 'alice';

      rl.close();

      console.log('\n\x1b[36m=== Executing Command ===\x1b[0m\n');

      process.argv = ['node', 'terminal.js', '--target', target, '--mode', mode, '--wallets', wallets];
      await executeCommand(category, operation, cmdOptions);

    } catch (error) {
      rl.close();
      exitOnError('\x1b[31m[interactive]\x1b[0m Error:', error);
    }
  });

const simulateCmd = program
  .command('simulate')
  .description('Run autonomous simulations');

simulateCmd
  .command('tictactoe')
  .description('Run autonomous tic-tac-toe game simulation')
  .requiredOption('--oracle-cid <cid>', 'Oracle CID')
  .requiredOption('--fiber-id <id>', 'State machine fiber ID')
  .option('--delay <ms>', 'Delay between moves in milliseconds', '2000')
  .option('--games <n>', 'Number of games to play', '1')
  .action(async (options) => {
    try {
      const fs = require('fs');
      const path = require('path');

      console.log('\n\x1b[36m=== Tic-Tac-Toe Autonomous Simulation ===\x1b[0m\n');
      console.log(`Oracle CID: ${options.oracleCid}`);
      console.log(`Fiber ID: ${options.fiberId}`);
      console.log(`Games to play: ${options.games}`);
      console.log(`Move delay: ${options.delay}ms\n`);

      const env = getMetagraphEnv(program.opts().target);
      const walletsToUse = {};

      const walletNames = program.opts().wallets.split(',');
      walletNames.forEach(name => {
        walletsToUse[name] = generateWallet(name.trim());
      });

      const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

      // Simple AI strategy: prioritize center, then corners, then edges
      const getBestMove = (board) => {
        const availableCells = board.map((cell, idx) => cell === null ? idx : null).filter(idx => idx !== null);

        if (availableCells.length === 0) return null;

        // Priority order: center (4), corners (0,2,6,8), edges (1,3,5,7)
        const center = 4;
        const corners = [0, 2, 6, 8];
        const edges = [1, 3, 5, 7];

        if (availableCells.includes(center)) return center;

        const availableCorners = corners.filter(c => availableCells.includes(c));
        if (availableCorners.length > 0) {
          return availableCorners[Math.floor(Math.random() * availableCorners.length)];
        }

        return availableCells[Math.floor(Math.random() * availableCells.length)];
      };

      for (let gameNum = 1; gameNum <= parseInt(options.games); gameNum++) {
        console.log(`\n\x1b[33m=== Game ${gameNum} ===\x1b[0m\n`);

        // Start game
        console.log('\x1b[36m[Step 1]\x1b[0m Starting game...');
        const gameId = crypto.randomUUID();
        const startEvent = {
          ProcessFiberEvent: {
            fiberId: options.fiberId,
            event: {
              eventType: { value: "start_game" },
              payload: {
                playerX: "DAG88MPZSPzWqEcCTkrs6hPjcdePPMyKhxUnPQU5",
                playerO: "DAG7Fqp72nH6FoVPCdFxm2QuBnVpFAv38kc9HPUr",
                gameId: gameId,
                timestamp: new Date().toISOString()
              },
              idempotencyKey: null
            }
          }
        };

        await sendDataTransaction(startEvent, walletsToUse, env);
        await delay(parseInt(options.delay));

        // Play game
        let currentPlayer = 'X';
        let moveCount = 0;
        let gameOver = false;
        let board = Array(9).fill(null);

        while (!gameOver && moveCount < 9) {
          console.log(`\n\x1b[36m[Move ${moveCount + 1}]\x1b[0m Player ${currentPlayer}'s turn`);

          const cell = getBestMove(board);
          if (cell === null) break;

          board[cell] = currentPlayer;

          const moveEvent = {
            ProcessFiberEvent: {
              fiberId: options.fiberId,
              event: {
                eventType: { value: "make_move" },
                payload: {
                  player: currentPlayer,
                  cell: cell,
                  timestamp: new Date().toISOString()
                },
                idempotencyKey: null
              }
            }
          };

          await sendDataTransaction(moveEvent, walletsToUse, env);
          await delay(parseInt(options.delay));

          // Display board
          console.log('\nCurrent board:');
          for (let i = 0; i < 9; i += 3) {
            console.log(` ${board[i] || '-'} | ${board[i+1] || '-'} | ${board[i+2] || '-'} `);
            if (i < 6) console.log('---|---|---');
          }

          // Check for win (simple check, oracle does the real validation)
          const checkWin = (b, p) => {
            const wins = [
              [0,1,2], [3,4,5], [6,7,8], // rows
              [0,3,6], [1,4,7], [2,5,8], // cols
              [0,4,8], [2,4,6]            // diagonals
            ];
            return wins.some(w => w.every(i => b[i] === p));
          };

          if (checkWin(board, currentPlayer)) {
            console.log(`\n\x1b[32m🎉 Player ${currentPlayer} wins!\x1b[0m`);
            gameOver = true;
          } else if (moveCount === 8) {
            console.log('\n\x1b[33m🤝 Game ended in a draw!\x1b[0m');
            gameOver = true;
          }

          currentPlayer = currentPlayer === 'X' ? 'O' : 'X';
          moveCount++;
        }

        if (gameNum < parseInt(options.games)) {
          console.log('\n\x1b[36m[Reset]\x1b[0m Resetting board for next game...');
          const resetEvent = {
            ProcessFiberEvent: {
              fiberId: options.fiberId,
              event: {
                eventType: { value: "reset_board" },
                payload: {
                  timestamp: new Date().toISOString()
                },
                idempotencyKey: null
              }
            }
          };

          await sendDataTransaction(resetEvent, walletsToUse, env);
          await delay(parseInt(options.delay) * 2);
          board = Array(9).fill(null);
        }
      }

      console.log('\n\x1b[32m✅ Simulation complete!\x1b[0m\n');

    } catch (error) {
      exitOnError('\x1b[31m[simulate/tictactoe]\x1b[0m Error:', error);
    }
  });

program.addCommand(stateMachineCmd);
program.addCommand(oracleCmd);
program.addCommand(queryCmd);
program.addCommand(simulateCmd);

program.parse(process.argv);

module.exports = { program };