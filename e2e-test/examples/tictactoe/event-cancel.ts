export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const eventData = context.eventData as Record<string, unknown> | undefined;

  const walletKeys = Object.keys(wallets);
  const requestingWallet = wallets[walletKeys[0]];

  return {
    eventName: 'cancel_game',
    payload: {
      requestedBy: (eventData?.requestedBy as string) || requestingWallet.address,
      reason: (eventData?.reason as string) || 'Game cancelled by user',
    },
  };
};
