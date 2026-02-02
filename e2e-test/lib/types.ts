import type { WalletInfo } from './generateWallet.ts';

/**
 * States map used by validators — maps ML0 checkpoint URLs to their
 * initial and final checkpoint states.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type StatesMap = Record<string, { initial: any; final: any }>;

export type Wallets = Record<string, WalletInfo>;

/**
 * Generic generator/validator function types for dynamic dispatch.
 * The specific option types are checked within each lib module;
 * at the terminal level we use these loose signatures.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type GeneratorFn = (args: any) => unknown;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type ValidatorFn = (args: any) => void | Promise<void>;
