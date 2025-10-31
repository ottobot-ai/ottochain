require('dotenv').config();

const { Command } = require('commander');
const crypto = require("crypto")
const axios = require('axios');
const generateProof = require("./lib/generateProof.js");
const generateWallet = require("./lib/generateWallet.js");
const sendDataTransactionsUsingUrls = require("./lib/sendDataTransaction.js");
const getMetagraphEnv = require("./lib/metagraphEnv.js");

const fungibleProgram = require("./lib/contract/fungible/program.js")

const program = new Command()
  .name('Smart Contract CLI')
  .description('A CLI for interacting with different contract types on the metagraph.')
  .requiredOption('--wallets <wallets>', 'Comma-separated list of signers from [alice, bob, charlie, diane, james]')
  .option('--address <address>', 'Choose a fixed UUID for interacting with. One of: <zulu, yankee, xray, whiskey, victor>. Defaults to random UUID if none given')
  .option('--target <target>', 'Determines the target URLs for HTTP requests. One of [local, remote, ci]. Default \"local\"', "local")
  .option('--mode <mode>', "Operation mode: [send', 'validate', 'send+validate'] (default: 'send+validate')", 'send+validate')
  .option('--waitTime <N (sec)>', 'The number of seconds to wait, after sending a data transaction, before beginging to query the metagraph state. Default 5 sec', 5)
  .option('--retryDelay <N (sec)>', 'The number of seconds to wait between each retry when querying the metagraph. Default 3 sec', 3)
  .option('--maxRetries <N>', 'The number of attempts at retrieving the expected state from the metagraph. Default 5', 5)
  .addCommand(fungibleProgram());

const exitOnError = (contextMessage, err) => {
  console.error(contextMessage, err);
  process.exit(1);
}

const parseSubCommands = (cmd) =>
  cmd.args.reduce(
    (acc, arg) => {
      const found = acc.commands.length
        ? acc.commands[acc.commands.length - 1].commands.find(subCmd => subCmd.name() === arg)
        : cmd.commands.find(subCmd => subCmd.name() === arg);

      if (found) {
        acc.commands.push(found);
        acc.options = { ...acc.options, ...found.opts() };
      }

      return acc;
    },
    { commands: [], options: {} }
  );

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
        `\x1b[33m[sendDataTransaction]\x1b[36m Client request:\x1b[0m ${JSON.stringify(signedUpdate)}`
      );
  
      const responses = await Promise.allSettled([
        sendDataTransactionsUsingUrls(signedUpdate, node1DataL1),
        sendDataTransactionsUsingUrls(signedUpdate, node2DataL1),
        sendDataTransactionsUsingUrls(signedUpdate, node3DataL1)
      ]);
  
      const fulfilledResponses = responses.filter(result => result.status === "fulfilled");
  
      if (fulfilledResponses.length > 0) {
        console.log(`\x1b[33m[sendDataTransaction]\x1b[32m Successful responses from ${fulfilledResponses.length} nodes`);
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
  programOpts,
  wallets,
  maxRetries, 
  retryDelayMs
) => {
  const validateWithRetries = async (currentAttempt, statesMap) => {
    console.error(`\x1b[33m[validate]\x1b[0m Initiating attempt (${currentAttempt}/${maxRetries})...`);
    try {
      await txValidation({ cid, statesMap, options: programOpts, wallets });
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
  }

  const _statesMap = await updateFinalStates(statesMap);
  await validateWithRetries(1, _statesMap);
}

const main = async () => {
  program.parse(process.argv)
  const invoked = parseSubCommands(program);
  const options = { ...program.opts(), ...invoked.options };

  try {
    const { globalL0Url, node1ML0, node2ML0, node3ML0, node1DataL1, node2DataL1, node3DataL1 } = getMetagraphEnv(options.target);
    const registry = JSON.parse(process.env.CONTRACTS);

    const kind = invoked.commands[0].name();
    const stage = invoked.commands[1].name();
    const cid = options.address ? (registry[options.address] ? registry[options.address] : options.address) : crypto.randomUUID();
    const wallets = [...new Set(options.wallets ? options.wallets.split(',') : ["alice"])].reduce((acc, user) => {
      acc[user] = generateWallet(globalL0Url, user)
      return acc;
    }, {});

    const { generator, validator } = require(`./lib/contract/${kind}/${stage}.js`);
    const message = generator({ cid, wallets, options: invoked.options });

    const maxRetries = options.maxRetries;
    const retryDelayMs = options.retryDelay * 1000;

    const ml0Env = [node1ML0, node2ML0, node3ML0].map(a => a + `/data-application/checkpoint`);
    const dataEnv = { node1DataL1, node2DataL1, node3DataL1 };

    switch (options.mode) {
      case 'send': {
        sendDataTransaction(message, wallets, dataEnv)
          .then(() => {
            process.exit(0);
          }).catch((err) => {
            exitOnError("\x1b[31m[generateContractTransaction]\x1b[0m Transaction generation failed:", err);
          })
      }

      case "validate": {
        validate(validator, cid, statesMap, invoked.options, wallets, maxRetries, retryDelayMs)
          .then(() => {
            process.exit(0);
          }).catch((err) => {
            exitOnError("\x1b[31m[generateContractTransaction]\x1b[0m Validation failed:", err);
          })
      }

      case 'send+validate':
      default: {


        let initialStates;

        getInitialStates(ml0Env)
          .then((res) => {
            initialStates = res;
            console.log("\x1b[33m[generateContractTransaction]\x1b[0m Fetched initial states from ML0 endpoints.");
          }).catch((err) => {
            exitOnError("\x1b[31m[generateContractTransaction]\x1b[0m Fetching initial states failed:", err);
          })

          .then(() => {
            return sendDataTransaction(message, wallets, dataEnv);
          }).catch((err) => {
            exitOnError("\x1b[31m[generateContractTransaction]\x1b[0m Transaction generation failed:", err);
          })

          .then(() => {
            const waitTimeMs = options.waitTime * 1000
            console.log(`\x1b[33m[generateContractTransaction]\x1b[0m Waiting ${options.waitTime} seconds before validation...`);
            return new Promise((resolve) => setTimeout(resolve, waitTimeMs));
          }).catch((err) => {
            exitOnError("\x1b[31m[generateContractTransaction]\x1b[0m Sending transaction failed:", err);
          })

          .then(() => {
            return validate(validator, cid, initialStates, invoked.options, wallets, maxRetries, retryDelayMs);
          }).catch((err) => {
            exitOnError("\x1b[31m[generateContractTransaction]\x1b[0m Validation failed:", err);
          })

          .then(() => {
            process.exit(0);
          });

      }
    }
  } catch (error) {
    console.error("\x1b[33m[generateContractTransaction]\x1b[31m main program encountered an error during execution:\x1b[0m", error);
    process.exit(1);
  }
};

if (require.main === module) {
  main();
}

module.exports = { main, program };
