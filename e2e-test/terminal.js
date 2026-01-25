require('dotenv').config();

const { Command } = require('commander');
const crypto = require("crypto");
const axios = require('axios');
const generateProof = require("./lib/generateProof.js");
const generateWallet = require("./lib/generateWallet.js");
const sendDataTransactionsUsingUrls = require("./lib/sendDataTransaction.js");
const getMetagraphEnv = require("./lib/metagraphEnv.js");

const program = new Command()
  .name('ottochain-terminal')
  .description('Interactive CLI for testing Ottochain state machines and script oracles')
  .option('--address <address>', 'Choose a fixed UUID for the resource. Defaults to random UUID if not provided')
  .option('--target <target>', 'Target environment: [local, remote, ci]. Default: "local"', "local")
  .option('--mode <mode>', "Operation mode: ['send', 'validate', 'send+validate'] (default: 'send+validate')", 'send+validate')
  .option('--waitTime <N>', 'Seconds to wait after sending before validation. Default: 5', '5')
  .option('--retryDelay <N>', 'Seconds between validation retries. Default: 3', '3')
  .option('--maxRetries <N>', 'Max validation retry attempts. Default: 5', '5')
  .option('--wallets <wallets>', 'Comma-separated list of signers [alice, bob, charlie, diane, james]. Default: alice', 'alice');

/**
 * Load a file that can be either JSON or JS module.
 * JS files should export a function that receives a context object.
 * JSON files are parsed and returned as-is.
 *
 * @param {string} filePath - Path to file (with or without extension)
 * @param {object} context - Context object passed to JS modules { wallets, session, eventData }
 * @returns {object} The loaded/generated data
 */
function loadFileOrModule(filePath, context = {}) {
  const fs = require('fs');

  // Determine paths based on extension
  const hasJsExt = filePath.endsWith('.js');
  const hasJsonExt = filePath.endsWith('.json');
  const hasNoExt = !hasJsExt && !hasJsonExt;

  let jsPath, jsonPath;
  if (hasNoExt) {
    jsPath = filePath + '.js';
    jsonPath = filePath + '.json';
  } else if (hasJsExt) {
    jsPath = filePath;
    jsonPath = filePath.replace(/\.js$/, '.json');
  } else {
    jsPath = filePath.replace(/\.json$/, '.js');
    jsonPath = filePath;
  }

  // Prefer .js if it exists
  if (fs.existsSync(jsPath)) {
    // Clear require cache to ensure fresh load
    delete require.cache[require.resolve(jsPath)];
    const moduleExport = require(jsPath);
    // If it's a function, call it with context; otherwise return as-is
    return typeof moduleExport === 'function' ? moduleExport(context) : moduleExport;
  }

  // Fall back to JSON
  if (fs.existsSync(jsonPath)) {
    return JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
  }

  throw new Error(`File not found: ${filePath} (tried ${jsPath} and ${jsonPath})`);
}

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
    const combined = examples.filter(e => e.type === 'combined');

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

    if (cmdOptions.type === 'all' && combined.length > 0) {
      console.log('\x1b[33mCombined Examples (Oracle + State Machine):\x1b[0m');
      combined.forEach(e => {
        console.log(`  \x1b[36m${e.dir}\x1b[0m - ${e.name}`);
        console.log(`    ${e.description}`);
      });
      console.log('');
    }

    console.log('Use \x1b[36m--example <name>\x1b[0m to see detailed information about an example');
    console.log('Use \x1b[36mrun --example <name> --list\x1b[0m to see available test flows\n');
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
      console.log('\x1b[36m\n=== Ottochain Interactive Terminal ===\x1b[0m\n');

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

// Run Command - Execute predefined test flows
const runCmd = new Command('run')
  .description('Execute predefined test flows from examples')
  .option('-e, --example <name>', 'Example to run (e.g., simple-order, counter-oracle, tictactoe)')
  .option('-f, --flow <number|name>', 'Test flow by number (1, 2, ...) or name')
  .option('-l, --list', 'List available test flows')
  .addHelpText('after', `
Examples:
  $ node terminal.js run -l                    # List all examples and their test flows
  $ node terminal.js run -e simple-order       # Run the default flow for simple-order
  $ node terminal.js run -e simple-order -l    # List flows for simple-order only
  $ node terminal.js run -e simple-order -f 2  # Run flow #2
  $ node terminal.js run -e simple-order -f cancel   # Match flow by name
`)
  .action(async (cmdOptions) => {
    const fs = require('fs');
    const path = require('path');

    const globalOpts = program.opts();
    const examplesDir = path.join(__dirname, 'examples');

    // Helper to load all examples (supports both .json and .js)
    const loadExamples = () => {
      return fs.readdirSync(examplesDir)
        .filter(f => fs.statSync(path.join(examplesDir, f)).isDirectory())
        .map(dir => {
          const jsPath = path.join(examplesDir, dir, 'example.js');
          const jsonPath = path.join(examplesDir, dir, 'example.json');
          try {
            if (fs.existsSync(jsPath)) {
              delete require.cache[require.resolve(jsPath)];
              const mod = require(jsPath);
              const example = typeof mod === 'function' ? mod({}) : mod;
              return { dir, ...example };
            } else if (fs.existsSync(jsonPath)) {
              return { dir, ...JSON.parse(fs.readFileSync(jsonPath, 'utf8')) };
            }
            return null;
          } catch { return null; }
        })
        .filter(e => e !== null && e.testFlows && e.testFlows.length > 0);
    };

    // If no example specified, show all examples with their flows
    if (!cmdOptions.example) {
      const examples = loadExamples();

      if (examples.length === 0) {
        console.error('\x1b[31mNo examples with test flows found\x1b[0m');
        process.exit(1);
      }

      console.log('\x1b[36m\nAvailable Test Flows:\x1b[0m\n');
      examples.forEach(example => {
        console.log(`\x1b[33m${example.dir}\x1b[0m - ${example.name}`);
        console.log(`  ${example.description}`);
        example.testFlows.forEach((flow, i) => {
          console.log(`    ${i + 1}) ${flow.name} (${flow.steps.length} steps)`);
        });
        console.log('');
      });

      console.log('Run a flow with: \x1b[36mnode terminal.js run --example <name>\x1b[0m\n');
      process.exit(0);
    }

    const exampleJsPath = path.join(examplesDir, cmdOptions.example, 'example.js');
    const exampleJsonPath = path.join(examplesDir, cmdOptions.example, 'example.json');

    if (!fs.existsSync(exampleJsPath) && !fs.existsSync(exampleJsonPath)) {
      console.error(`\x1b[31mExample "${cmdOptions.example}" not found\x1b[0m`);
      console.log(`\nAvailable examples:`);
      fs.readdirSync(examplesDir)
        .filter(f => fs.statSync(path.join(examplesDir, f)).isDirectory())
        .forEach(dir => console.log(`  - ${dir}`));
      process.exit(1);
    }

    // Load example (prefer .js over .json)
    let example;
    if (fs.existsSync(exampleJsPath)) {
      delete require.cache[require.resolve(exampleJsPath)];
      const mod = require(exampleJsPath);
      example = typeof mod === 'function' ? mod({}) : mod;
    } else {
      example = JSON.parse(fs.readFileSync(exampleJsonPath, 'utf8'));
    }

    if (!example.testFlows || example.testFlows.length === 0) {
      console.error(`\x1b[31mNo test flows defined for "${cmdOptions.example}"\x1b[0m`);
      process.exit(1);
    }

    if (cmdOptions.list) {
      console.log(`\x1b[36m\nTest flows for ${example.name}:\x1b[0m\n`);
      example.testFlows.forEach((flow, i) => {
        console.log(`  ${i + 1}) \x1b[33m${flow.name}\x1b[0m`);
        console.log(`     ${flow.description}`);
        console.log(`     Steps: ${flow.steps.length}`);
      });
      console.log('');
      process.exit(0);
    }

    // Find the flow to run
    let flow;
    if (cmdOptions.flow) {
      // Try to match by number first (1-indexed)
      const flowNum = parseInt(cmdOptions.flow);
      if (!isNaN(flowNum) && flowNum >= 1 && flowNum <= example.testFlows.length) {
        flow = example.testFlows[flowNum - 1];
      } else {
        // Try to match by name (partial, case-insensitive)
        flow = example.testFlows.find(f =>
          f.name.toLowerCase().includes(cmdOptions.flow.toLowerCase())
        );
      }
      if (!flow) {
        console.error(`\x1b[31mFlow "${cmdOptions.flow}" not found\x1b[0m`);
        console.log(`\nAvailable flows:`);
        example.testFlows.forEach((f, i) => console.log(`  ${i + 1}) ${f.name}`));
        process.exit(1);
      }
    } else {
      flow = example.testFlows[0];
    }

    console.log(`\x1b[36m\n=== Running: ${flow.name} ===\x1b[0m`);
    console.log(`${flow.description}\n`);

    try {
      const { globalL0Url, node1ML0, node2ML0, node3ML0, node1DataL1, node2DataL1, node3DataL1 } = getMetagraphEnv(globalOpts.target);

      const wallets = [...new Set(globalOpts.wallets.split(','))].reduce((acc, user) => {
        acc[user] = generateWallet(globalL0Url, user);
        return acc;
      }, {});

      const maxRetries = parseInt(globalOpts.maxRetries);
      const retryDelayMs = parseInt(globalOpts.retryDelay) * 1000;
      const waitTimeMs = parseInt(globalOpts.waitTime) * 1000;
      const ml0Env = [node1ML0, node2ML0, node3ML0].map(a => a + `/data-application/checkpoint`);
      const dataEnv = { node1DataL1, node2DataL1, node3DataL1 };

      // Session state to track CIDs between steps
      const session = {
        cid: globalOpts.address || crypto.randomUUID(),
        oracleCid: null
      };

      for (let i = 0; i < flow.steps.length; i++) {
        const step = flow.steps[i];
        console.log(`\x1b[33m[Step ${i + 1}/${flow.steps.length}]\x1b[0m ${step.action}...`);

        let generator, validator, message, stepOptions;

        // Context for JS module loading
        const loadContext = { wallets, session, eventData: step.eventData };

        switch (step.action) {
          case 'create':
          case 'createStateMachine': {
            const definition = loadFileOrModule(path.join(examplesDir, cmdOptions.example, step.definition), loadContext);
            const initialData = loadFileOrModule(path.join(examplesDir, cmdOptions.example, step.initialData), loadContext);

            stepOptions = { definition, initialData };

            const libModule = require('./lib/state-machine/createFiber.js');
            generator = libModule.generator;
            validator = libModule.validator;
            message = generator({
              cid: session.cid,
              wallets,
              options: stepOptions
            });
            break;
          }

          case 'createOracle': {
            // For oracle, use well-known UUID from example config or generate random
            session.oracleCid = example.oracleCid || crypto.randomUUID();
            const definition = loadFileOrModule(path.join(examplesDir, cmdOptions.example, step.definition), loadContext);

            stepOptions = { oracleDefinition: definition };

            const libModule = require('./lib/oracle/createOracle.js');
            generator = libModule.generator;
            validator = libModule.validator;
            message = generator({
              cid: session.oracleCid,
              wallets,
              options: stepOptions
            });
            console.log(`   Oracle CID: ${session.oracleCid}`);
            break;
          }

          case 'processEvent': {
            // Load event data (JS modules handle injection automatically)
            let eventData = loadFileOrModule(path.join(examplesDir, cmdOptions.example, step.event), loadContext);

            // Override event data if provided in step (for JSON files or additional overrides)
            if (step.eventData) {
              eventData.payload = { ...eventData.payload, ...step.eventData };
            }

            stepOptions = { eventData, expectedState: step.expectedState };

            const libModule = require('./lib/state-machine/processEvent.js');
            generator = libModule.generator;
            validator = libModule.validator;
            message = generator({
              cid: session.cid,
              wallets,
              options: stepOptions
            });
            break;
          }

          case 'invoke': {
            let args = null;
            if (step.args) {
              args = loadFileOrModule(path.join(examplesDir, cmdOptions.example, step.args), loadContext);
            }

            stepOptions = { method: step.method, args, expectedResult: step.expectedResult };

            const libModule = require('./lib/oracle/invokeOracle.js');
            generator = libModule.generator;
            validator = libModule.validator;
            message = generator({
              cid: session.oracleCid,
              wallets,
              options: stepOptions
            });
            break;
          }

          default:
            console.error(`\x1b[31mUnknown action: ${step.action}\x1b[0m`);
            process.exit(1);
        }

        // Send and validate - pass the complete stepOptions to validator
        const initialStates = await getInitialStates(ml0Env);
        await sendDataTransaction(message, wallets, dataEnv);
        await new Promise(resolve => setTimeout(resolve, waitTimeMs));
        const validationOptions = { ...stepOptions, expectedState: step.expectedState };
        const useOracleCid = step.action.includes('Oracle') || step.action === 'invoke';
        await validate(validator, useOracleCid ? session.oracleCid : session.cid, initialStates, validationOptions, wallets, maxRetries, retryDelayMs);

        console.log(`\x1b[32m   ✓ Step completed\x1b[0m`);
      }

      console.log(`\x1b[32m\n✓ Flow completed successfully!\x1b[0m`);
      console.log(`  State Machine CID: ${session.cid}`);
      if (session.oracleCid) {
        console.log(`  Oracle CID: ${session.oracleCid}`);
      }
      console.log('');
      process.exit(0);

    } catch (error) {
      exitOnError(`\x1b[31m[run]\x1b[0m Flow execution failed:`, error);
    }
  });

program.addCommand(stateMachineCmd);
program.addCommand(oracleCmd);
program.addCommand(queryCmd);
program.addCommand(runCmd);

program.parse(process.argv);

module.exports = { program };