/**
 * Start Game Event
 *
 * Triggers the setup -> playing transition with player addresses and game ID.
 */

const crypto = require('crypto');

module.exports = function(context) {
  // context provides: { wallets, session, eventData }
  const { wallets, eventData } = context;

  const walletKeys = Object.keys(wallets);
  const playerXWallet = wallets[walletKeys[0]];
  const playerOWallet = wallets[walletKeys[1]] || playerXWallet;

  return {
    eventType: { value: 'start_game' },
    payload: {
      playerX: eventData?.playerX || playerXWallet.account.address,
      playerO: eventData?.playerO || playerOWallet.account.address,
      gameId: eventData?.gameId || crypto.randomUUID()
    },
    idempotencyKey: null
  };
};
