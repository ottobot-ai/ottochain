/**
 * Make Move Event
 *
 * Processes a player's move on the board.
 */

module.exports = function(context) {
  // context provides: { wallets, session, eventData }
  const { eventData } = context;

  return {
    eventType: { value: 'make_move' },
    payload: {
      player: eventData?.player || 'X',
      cell: eventData?.cell ?? 0
    },
    idempotencyKey: null
  };
};
