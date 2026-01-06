const { Command } = require('commander');

const generateProof = require("./lib/generateProof.js");
const generateWallet = require("./lib/generateWallet.js");
const sendDataTransactionsUsingUrls = require("./lib/sendDataTransaction.js");
const getMetagraphEnv = require("./lib/metagraphEnv.js");

const program = new Command();
program
  .name('ottochain transaction generator')
  .description('A script to generate an content transaction for the ottochain metagraph.')
  .requiredOption('--type <type>', 'The type of transaction, either "owned" or "versioned"')
  .requiredOption('--stage <stage>', 'The lifecycle stage to generate, sign, and transmit to the metagraph.')
  .option('--user <user>', 'Provide a user to search for in the proconfigured directory.')
  .option('--remote', 'Flag to target a remote metagraph instance.')
  .parse(process.argv);

const options = program.opts();
const buildTx = require(`./resources/${options.type}/${options.stage}.js`);

const sendDataTransaction = async () => {
  const { globalL0Url, node1DataL1, node2DataL1, node3DataL1 } = getMetagraphEnv(options.remote);
  const { account, privateKey } = generateWallet(globalL0Url, options.user);
  const message = buildTx();
  const proof = await generateProof(message, privateKey, account);
  const signedUpdate = {
    value: { ...message },
    proofs: [ proof ],
  };

  console.log(`\nClient request: ${JSON.stringify(signedUpdate)}`);

  await Promise.all([
    sendDataTransactionsUsingUrls(signedUpdate, node1DataL1),
    sendDataTransactionsUsingUrls(signedUpdate, node2DataL1),
    sendDataTransactionsUsingUrls(signedUpdate, node3DataL1)
  ])
};

sendDataTransaction();
