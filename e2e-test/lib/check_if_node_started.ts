import { HttpClient } from '@ottochain/sdk';

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function checkUrl(
  url: string,
  clusterName: string,
  attempt: number,
  maxAttempts: number,
  retryDelay: number
): Promise<void> {
  try {
    const client = new HttpClient(url);
    await client.get<unknown>('/');
    console.log(`${clusterName} started!`);
  } catch {
    if (attempt >= maxAttempts) {
      throw new Error(
        `Error: ${clusterName} did not start after ${maxAttempts} attempts.`
      );
    }

    console.log(
      `${clusterName} still booting... waiting ${retryDelay} sec before retrying (${attempt + 1}/${maxAttempts})`
    );

    await sleep(retryDelay * 1000);
    return checkUrl(url, clusterName, attempt + 1, maxAttempts, retryDelay);
  }
}

function parseArg(key: string, defaultValue: string | null): string | null {
  const found = process.argv.find((arg) => arg.startsWith(`-${key}=`));
  return found ? found.split('=')[1] : defaultValue;
}

export async function main(): Promise<void> {
  const urlParam = parseArg('url', null);
  const clusterNameParam = parseArg('cluster_name', 'my-cluster') ?? 'my-cluster';
  const maxAttempts = parseInt(parseArg('maxAttempts', '5') ?? '5');
  const retryDelay = parseInt(parseArg('retryDelay', '5') ?? '5');

  if (!urlParam) {
    throw new Error('Url should be provided, e.g. --url=http://example.com');
  }

  console.log(`Starting to check if url: ${urlParam} is started...`);
  return checkUrl(urlParam, clusterNameParam, 0, maxAttempts, retryDelay);
}

// Allow direct invocation
const isMainModule = process.argv[1]?.includes('check_if_node_started');
if (isMainModule) {
  main()
    .then(() => process.exit(0))
    .catch((err) => {
      console.error(err);
      process.exit(1);
    });
}
