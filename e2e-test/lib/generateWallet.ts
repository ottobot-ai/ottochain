import 'dotenv/config';
import { keyPairFromPrivateKey, generateKeyPair } from '@ottochain/sdk';
import type { KeyPair } from '@ottochain/sdk';

export type WalletInfo = KeyPair;

export default function generateWallet(
  _globalL0Url: string,
  user: string
): WalletInfo {
  const directory = JSON.parse(process.env.USERS || '{}');

  if (directory[user]) {
    console.log(`\x1b[33m[generateWallet]\x1b[0m Loading ${user} from env`);
    return keyPairFromPrivateKey(directory[user]);
  }

  console.log(
    `\x1b[33m[generateWallet]\x1b[0m Unable to find user: ${user} in env, creating one-time secret!`
  );
  return generateKeyPair();
}
