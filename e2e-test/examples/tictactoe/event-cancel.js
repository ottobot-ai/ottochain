/**
 * Cancel Game Event
 *
 * Cancels the game with a reason.
 */

module.exports = function(context) {
  // context provides: { wallets, session, eventData }
  const { wallets, eventData } = context;

  const walletKeys = Object.keys(wallets);
  const requestingWallet = wallets[walletKeys[0]];

  return {
    eventType: { value: 'cancel_game' },
    payload: {
      requestedBy: eventData?.requestedBy || requestingWallet.account.address,
      reason: eventData?.reason || 'Game cancelled by user'
    },
    idempotencyKey: null
  };
};
