import crypto from 'crypto';

export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const eventData = context.eventData as Record<string, unknown> | undefined;

  const walletKeys = Object.keys(wallets);
  const playerXWallet = wallets[walletKeys[0]];
  const playerOWallet = wallets[walletKeys[1]] || playerXWallet;

  return {
    eventName: 'start_game',
    payload: {
      playerX: (eventData?.playerX as string) || playerXWallet.address,
      playerO: (eventData?.playerO as string) || playerOWallet.address,
      gameId: (eventData?.gameId as string) || crypto.randomUUID(),
    },
  };
};
