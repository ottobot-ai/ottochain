/**
 * Ottochain E2E Test Runner
 *
 * Discovers all examples with testFlows and runs them automatically.
 * Reports pass/fail per flow with step-level detail.
 * Exits with non-zero code on any failure (CI-friendly).
 *
 * Usage: npx tsx runner.ts [--target local|ci|remote] [--wallets alice,bob]
 */
import 'dotenv/config';

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import generateWallet from './lib/generateWallet.ts';
import sendSignedUpdate from './lib/sendDataTransaction.ts';
import getMetagraphEnv from './lib/metagraphEnv.ts';
import type { StatesMap, Wallets, GeneratorFn, ValidatorFn } from './lib/types.ts';
import { HttpClient } from '@ottochain/sdk';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const examplesDir = path.join(__dirname, 'examples');

// ---------------------------------------------------------------------------
// CLI arguments (minimal — no commander needed)
// ---------------------------------------------------------------------------

function parseArgs() {
  const args = process.argv.slice(2);
  const opts: Record<string, string> = {
    target: 'local',
    wallets: 'alice',
    waitTime: '5',
    retryDelay: '5',
    maxRetries: '20',
  };

  for (let i = 0; i < args.length; i++) {
    if (args[i].startsWith('--') && i + 1 < args.length) {
      opts[args[i].slice(2)] = args[i + 1];
      i++;
    }
  }

  return opts;
}

// ---------------------------------------------------------------------------
// Shared helpers (same as terminal.ts)
// ---------------------------------------------------------------------------

async function loadFileOrModule(
  filePath: string,
  context: Record<string, unknown> = {}
): Promise<unknown> {
  const hasJsExt = filePath.endsWith('.js');
  const hasJsonExt = filePath.endsWith('.json');
  const hasTsExt = filePath.endsWith('.ts');
  const hasNoExt = !hasJsExt && !hasJsonExt && !hasTsExt;

  let tsPath: string, jsPath: string, jsonPath: string;
  if (hasNoExt) {
    tsPath = filePath + '.ts';
    jsPath = filePath + '.js';
    jsonPath = filePath + '.json';
  } else if (hasTsExt) {
    tsPath = filePath;
    jsPath = filePath.replace(/\.ts$/, '.js');
    jsonPath = filePath.replace(/\.ts$/, '.json');
  } else if (hasJsExt) {
    tsPath = filePath.replace(/\.js$/, '.ts');
    jsPath = filePath;
    jsonPath = filePath.replace(/\.js$/, '.json');
  } else {
    tsPath = filePath.replace(/\.json$/, '.ts');
    jsPath = filePath.replace(/\.json$/, '.js');
    jsonPath = filePath;
  }

  for (const candidate of [tsPath, jsPath]) {
    if (fs.existsSync(candidate)) {
      const mod = await import(candidate);
      const exported = mod.default ?? mod;
      return typeof exported === 'function' ? exported(context) : exported;
    }
  }

  if (fs.existsSync(jsonPath)) {
    return JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
  }

  throw new Error(`File not found: ${filePath} (tried .ts, .js, .json)`);
}

async function getApplicationState(url: string): Promise<unknown> {
  const client = new HttpClient(url);
  return client.get<unknown>('');
}

async function getInitialStates(urls: string[]): Promise<StatesMap> {
  const initialStates = await Promise.all(
    urls.map((url) => getApplicationState(url))
  );

  const statesMap: StatesMap = {};
  for (let i = 0; i < urls.length; i++) {
    statesMap[urls[i]] = {
      initial: initialStates[i],
      final: null,
    };
  }

  return statesMap;
}

async function updateFinalStates(statesMap: StatesMap): Promise<StatesMap> {
  const urls = Object.keys(statesMap);
  const finalStates = await Promise.all(
    urls.map((url) => getApplicationState(url))
  );

  for (let i = 0; i < urls.length; i++) {
    statesMap[urls[i]].final = finalStates[i];
  }

  return statesMap;
}

async function validateWithRetries(
  txValidation: ValidatorFn,
  cid: string,
  statesMap: StatesMap,
  options: Record<string, unknown>,
  wallets: Wallets,
  maxRetries: number,
  retryDelayMs: number,
  ml0Urls: string[],
  attempt = 1
): Promise<void> {
  try {
    const updated = await updateFinalStates(statesMap);
    await txValidation({ cid, statesMap: updated, options, wallets, ml0Urls });
  } catch (err) {
    if (attempt >= maxRetries) {
      throw err;
    }
    await new Promise((resolve) => setTimeout(resolve, retryDelayMs));

    // Refresh final states for retry
    let refreshed: StatesMap;
    try {
      refreshed = await updateFinalStates(statesMap);
    } catch {
      throw err; // Can't refresh, re-throw original error
    }
    return validateWithRetries(
      txValidation, cid, refreshed, options, wallets,
      maxRetries, retryDelayMs, ml0Urls, attempt + 1
    );
  }
}

/**
 * Poll an ML0 endpoint until a condition is met.
 * Uses the ML0 custom routes (e.g. /v1/state-machines/{id}, /v1/oracles/{id}).
 */
async function waitForMl0Confirmation(
  ml0BaseUrl: string,
  entityPath: string,
  predicate: (data: unknown) => boolean,
  maxRetries: number,
  retryDelayMs: number,
  label: string
): Promise<void> {
  const url = `${ml0BaseUrl}/data-application/v1/${entityPath}`;
  const client = new HttpClient(url);
  process.stdout.write(`\n      ⏳ ML0 confirm ${label}`);

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const data = await client.get<unknown>('');
      if (predicate(data)) {
        process.stdout.write(' ✓\n');
        return;
      }
    } catch {
      // Entity may not exist yet — continue polling
    }
    process.stdout.write('.');
    if (attempt < maxRetries) {
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
    }
  }

  process.stdout.write(' ✗\n');
  throw new Error(
    `ML0 confirmation timed out for ${label} at ${url} after ${maxRetries} attempts`
  );
}

/**
 * Wait until a DL1 node's OnChain state reflects a fiber commit that matches
 * the expected sequence number (or simply exists, for create steps).
 *
 * Compares the DL1's /v1/onchain fiberCommits against what ML0 has already
 * confirmed, ensuring snapshot propagation (ML0 → GL0 → DL1) has completed
 * before the runner sends the next step.
 */
async function waitForDl1Sync(
  dl1BaseUrl: string,
  fiberId: string,
  expectedSeqNum: number | null,
  maxRetries: number,
  retryDelayMs: number,
  label: string
): Promise<void> {
  const url = `${dl1BaseUrl}/data-application/v1/onchain`;
  const client = new HttpClient(url);
  const seqLabel = expectedSeqNum === null ? 'exists' : `seq≥${expectedSeqNum}`;
  process.stdout.write(`      ⏳ DL1 sync ${fiberId.slice(0, 8)}… (${seqLabel})`);

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const onChain = (await client.get<unknown>('')) as {
        fiberCommits?: Record<string, { sequenceNumber?: number }>;
      } | null;

      if (onChain?.fiberCommits) {
        const commit = onChain.fiberCommits[fiberId];
        if (commit) {
          if (expectedSeqNum === null) {
            // Create step — just need the fiber to exist in DL1's OnChain
            process.stdout.write(' ✓\n');
            return;
          }
          if (commit.sequenceNumber !== undefined && commit.sequenceNumber >= expectedSeqNum) {
            process.stdout.write(' ✓\n');
            return;
          }
        }
      }
    } catch {
      // DL1 may not be ready yet — continue polling
    }
    process.stdout.write('.');
    if (attempt < maxRetries) {
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
    }
  }

  process.stdout.write(' ✗\n');
  throw new Error(
    `DL1 sync timed out for ${label} at ${url} after ${maxRetries} attempts ` +
      `(waiting for fiberId=${fiberId} seqNum=${expectedSeqNum ?? 'exists'})`
  );
}

// ---------------------------------------------------------------------------
// Example discovery
// ---------------------------------------------------------------------------

interface TestStep {
  action: string;
  definition?: string;
  initialData?: string;
  event?: string;
  eventData?: Record<string, unknown>;
  expectedState?: string;
  method?: string;
  args?: string;
  expectedResult?: unknown;
  [key: string]: unknown;
}

interface TestFlow {
  name: string;
  description: string;
  steps: TestStep[];
}

interface Example {
  dir: string;
  name: string;
  description: string;
  type: string;
  oracleFiberId?: string;
  testFlows: TestFlow[];
  [key: string]: unknown;
}

async function discoverExamples(): Promise<Example[]> {
  const dirs = fs
    .readdirSync(examplesDir)
    .filter((f) => fs.statSync(path.join(examplesDir, f)).isDirectory());

  const examples = await Promise.all(
    dirs.map(async (dir): Promise<Example | null> => {
      try {
        const example = (await loadFileOrModule(
          path.join(examplesDir, dir, 'example'),
          {}
        )) as Record<string, unknown>;

        if (!Array.isArray(example.testFlows) || example.testFlows.length === 0) {
          return null;
        }

        return { dir, ...example } as Example;
      } catch {
        return null;
      }
    })
  );

  return examples.filter((e): e is Example => e !== null);
}

// ---------------------------------------------------------------------------
// Flow execution
// ---------------------------------------------------------------------------

async function runFlow(
  example: Example,
  flow: TestFlow,
  env: ReturnType<typeof getMetagraphEnv>,
  wallets: Wallets,
  ml0Urls: string[],
  ml0Env: string[],
  dl1Urls: string[],
  maxRetries: number,
  retryDelayMs: number,
  waitTimeMs: number
): Promise<{ passed: boolean; error?: string; failedStep?: number }> {
  const session = {
    cid: crypto.randomUUID(),
    oracleFiberId: null as string | null,
  };

  for (let i = 0; i < flow.steps.length; i++) {
    const step = flow.steps[i];
    const stepLabel = `[Step ${i + 1}/${flow.steps.length}]`;
    process.stdout.write(`  ${stepLabel} ${step.action}...`);

    try {
      let generator: GeneratorFn;
      let validator: ValidatorFn;
      let message: unknown;
      let stepOptions: Record<string, unknown>;

      const loadContext = {
        wallets,
        session,
        eventData: step.eventData,
      };

      // Determine which fiber this step targets and fetch its current sequence number
      const isOracleStep =
        (step.action as string).includes('Oracle') || step.action === 'invoke';
      const isCreateStep =
        step.action === 'create' ||
        step.action === 'createStateMachine' ||
        step.action === 'createOracle';

      // For createOracle, oracleFiberId is assigned inside the switch below,
      // so we defer activeCid/entityPath until after the switch for create steps.
      let activeCid = isOracleStep ? session.oracleFiberId! : session.cid;
      let entityPath = isOracleStep
        ? `oracles/${activeCid}`
        : `state-machines/${activeCid}`;

      let preSendSeqNum = -1;
      if (!isCreateStep) {
        try {
          const ml0Client = new HttpClient(
            `${ml0Urls[0]}/data-application/v1/${entityPath}`
          );
          const existing = (await ml0Client.get<unknown>('')) as Record<string, unknown> | null;
          if (existing && typeof existing === 'object') {
            preSendSeqNum = (existing as { sequenceNumber?: number }).sequenceNumber ?? -1;
          }
        } catch {
          // Entity doesn't exist yet (expected for create steps)
        }
      }

      switch (step.action) {
        case 'create':
        case 'createStateMachine': {
          const definition = await loadFileOrModule(
            path.join(examplesDir, example.dir, step.definition!),
            loadContext
          );
          const initialData = await loadFileOrModule(
            path.join(examplesDir, example.dir, step.initialData!),
            loadContext
          );

          stepOptions = { definition, initialData };

          const libModule = await import('./lib/state-machine/createFiber.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: session.cid,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'createOracle': {
          session.oracleFiberId = (example.oracleFiberId as string) || crypto.randomUUID();
          const definition = await loadFileOrModule(
            path.join(examplesDir, example.dir, step.definition!),
            loadContext
          );

          stepOptions = { oracleDefinition: definition };

          const libModule = await import('./lib/oracle/createOracle.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: session.oracleFiberId,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'processEvent': {
          let eventData = (await loadFileOrModule(
            path.join(examplesDir, example.dir, step.event!),
            loadContext
          )) as Record<string, unknown>;

          if (step.eventData) {
            eventData = {
              ...eventData,
              payload: {
                ...(eventData.payload as Record<string, unknown>),
                ...(step.eventData as Record<string, unknown>),
              },
            };
          }

          stepOptions = {
            eventData,
            expectedState: step.expectedState,
            targetSequenceNumber: preSendSeqNum >= 0 ? preSendSeqNum : 0,
          };

          const libModule = await import('./lib/state-machine/processEvent.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: session.cid,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'invoke': {
          let args: unknown = null;
          if (step.args) {
            args = await loadFileOrModule(
              path.join(examplesDir, example.dir, step.args),
              loadContext
            );
          }

          stepOptions = {
            method: step.method,
            args,
            expectedResult: step.expectedResult,
            targetSequenceNumber: preSendSeqNum >= 0 ? preSendSeqNum : 0,
          };

          const libModule = await import('./lib/oracle/invokeOracle.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: session.oracleFiberId!,
            wallets,
            options: stepOptions,
          });
          break;
        }

        default:
          throw new Error(`Unknown action: ${step.action}`);
      }

      // Re-compute activeCid/entityPath after the switch — createOracle assigns
      // session.oracleFiberId inside the switch, so the pre-switch values may be stale.
      if (isCreateStep) {
        activeCid = isOracleStep ? session.oracleFiberId! : session.cid;
        entityPath = isOracleStep
          ? `oracles/${activeCid}`
          : `state-machines/${activeCid}`;
      }

      // Snapshot initial state before sending
      const initialStates = await getInitialStates(ml0Env);

      // Send with retries.
      // DL1 may temporarily reject mutations after a create step because its
      // OnChain cache hasn't refreshed yet (snapshot propagation delay).
      // When that happens the first attempt gets CidNotFound/FiberIdNotFound,
      // and subsequent retries with the *same* signed payload are rejected as
      // duplicate ("Invalid signature"). To work around this, we re-generate
      // and re-sign the message on every retry so each attempt has a unique
      // content hash for the DL1's dedup cache.
      let sendAttempt = 0;
      let currentMessage = message;
      while (true) {
        try {
          await sendSignedUpdate(currentMessage, wallets, dl1Urls);
          break;
        } catch (sendErr) {
          sendAttempt++;
          const errMsg = (sendErr as Error).message;
          if (sendAttempt >= maxRetries || !errMsg.includes('400')) {
            throw sendErr;
          }
          process.stdout.write(` (retry ${sendAttempt}/${maxRetries})...`);
          await new Promise((resolve) => setTimeout(resolve, retryDelayMs));

          // Re-generate the message so the next attempt has a fresh content hash.
          // For mutations, also re-fetch the current sequence number in case
          // the fiber was created/updated since the last attempt.
          if (!isCreateStep) {
            try {
              const ml0Client = new HttpClient(
                `${ml0Urls[0]}/data-application/v1/${entityPath}`
              );
              const existing = (await ml0Client.get<unknown>('')) as Record<string, unknown> | null;
              if (existing && typeof existing === 'object') {
                const freshSeqNum = (existing as { sequenceNumber?: number }).sequenceNumber ?? -1;
                if (freshSeqNum >= 0) {
                  (stepOptions as Record<string, unknown>).targetSequenceNumber = freshSeqNum;
                }
              }
            } catch {
              // Entity might not exist yet — keep current options
            }
          }
          currentMessage = generator!({
            cid: activeCid,
            wallets,
            options: stepOptions!,
          });
        }
      }

      // Poll ML0 to confirm the transaction was processed
      await waitForMl0Confirmation(
        ml0Urls[0],
        entityPath,
        (data) => {
          if (!data || typeof data !== 'object') return false;
          const record = data as { sequenceNumber?: number; status?: string };
          if (isCreateStep) {
            return record.status === 'Active';
          }
          // For transitions/invocations: sequenceNumber must have increased
          return (record.sequenceNumber ?? -1) > preSendSeqNum;
        },
        maxRetries,
        retryDelayMs,
        `${step.action} on ${activeCid}`
      );

      // Wait for DL1 OnChain state to catch up with ML0.
      // After ML0 confirms the step, the snapshot still needs to propagate
      // through GL0 → DL1 before the DL1's validation cache reflects the
      // new fiber commit. Without this, the next step's DL1 send may fail
      // with FiberIdNotFound (create) or SequenceNumberMismatch (mutation).
      {
        // Fetch the expected sequence number from ML0 (the source of truth)
        let expectedSeqNum: number | null = null;
        try {
          const ml0Client = new HttpClient(
            `${ml0Urls[0]}/data-application/v1/${entityPath}`
          );
          const record = (await ml0Client.get<unknown>('')) as {
            sequenceNumber?: number;
          } | null;
          expectedSeqNum = record?.sequenceNumber ?? null;
        } catch {
          // If we can't fetch from ML0, fall back to existence check only
        }

        await waitForDl1Sync(
          dl1Urls[0],
          activeCid,
          expectedSeqNum,
          maxRetries,
          retryDelayMs,
          `${step.action} DL1 sync for ${activeCid}`
        );
      }

      // Validate
      const validationOptions = {
        ...stepOptions!,
        expectedState: step.expectedState,
      };
      await validateWithRetries(
        validator!,
        activeCid,
        initialStates,
        validationOptions,
        wallets,
        maxRetries,
        retryDelayMs,
        ml0Urls
      );

      console.log(' \x1b[32mOK\x1b[0m');
    } catch (error) {
      console.log(' \x1b[31mFAIL\x1b[0m');
      return {
        passed: false,
        error: (error as Error).message,
        failedStep: i + 1,
      };
    }
  }

  return { passed: true };
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  const opts = parseArgs();

  console.log('\x1b[36m=== Ottochain E2E Test Runner ===\x1b[0m\n');

  // Setup environment
  const env = getMetagraphEnv(opts.target);
  const walletNames = [...new Set(opts.wallets.split(','))];
  const wallets = walletNames.reduce((acc: Wallets, user) => {
    acc[user] = generateWallet(env.globalL0Url, user);
    return acc;
  }, {});

  const ml0Urls = [env.node1ML0, env.node2ML0, env.node3ML0];
  const ml0Env = ml0Urls.map((a) => a + '/data-application/v1/checkpoint');
  const dl1Urls = [env.node1DataL1, env.node2DataL1, env.node3DataL1];
  const maxRetries = parseInt(opts.maxRetries);
  const retryDelayMs = parseInt(opts.retryDelay) * 1000;
  const waitTimeMs = parseInt(opts.waitTime) * 1000;

  // Discover examples
  const examples = await discoverExamples();

  if (examples.length === 0) {
    console.error('\x1b[31mNo examples with test flows found\x1b[0m');
    process.exit(1);
  }

  console.log(
    `Found ${examples.length} example(s): ${examples.map((e) => e.dir).join(', ')}\n`
  );

  // Run all flows
  const results: Array<{
    example: string;
    flow: string;
    steps: number;
    passed: boolean;
    error?: string;
    failedStep?: number;
  }> = [];

  for (const example of examples) {
    for (const flow of example.testFlows) {
      console.log(
        `\x1b[36m[${example.dir}]\x1b[0m Running: ${flow.name} (${flow.steps.length} steps)`
      );

      const result = await runFlow(
        example,
        flow,
        env,
        wallets,
        ml0Urls,
        ml0Env,
        dl1Urls,
        maxRetries,
        retryDelayMs,
        waitTimeMs
      );

      results.push({
        example: example.dir,
        flow: flow.name,
        steps: flow.steps.length,
        ...result,
      });

      if (result.passed) {
        console.log(`  \x1b[32mPASS: ${flow.name}\x1b[0m\n`);
      } else {
        console.log(
          `  \x1b[31mFAIL: ${flow.name}\x1b[0m (step ${result.failedStep})`
        );
        console.log(`  Error: ${result.error}\n`);
      }
    }
  }

  // Summary
  const passed = results.filter((r) => r.passed).length;
  const failed = results.filter((r) => !r.passed).length;
  const total = results.length;

  console.log('\x1b[36m=== Results ===\x1b[0m');
  console.log(`Passed: ${passed}/${total}`);
  console.log(`Failed: ${failed}/${total}`);

  if (failed > 0) {
    console.log('\n\x1b[31mFailed flows:\x1b[0m');
    results
      .filter((r) => !r.passed)
      .forEach((r) => {
        console.log(`  - [${r.example}] ${r.flow} (step ${r.failedStep}): ${r.error}`);
      });
  }

  console.log(`\nExit code: ${failed > 0 ? 1 : 0}`);
  process.exit(failed > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error('\x1b[31mRunner failed:\x1b[0m', err.message);
  process.exit(1);
});
