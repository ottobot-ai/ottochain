/**
 * Reset Board Event
 *
 * Resets the board for a new round while keeping player assignments.
 */

module.exports = function(context) {
  // context provides: { wallets, session, eventData }

  return {
    eventType: { value: 'reset_board' },
    payload: {},
    idempotencyKey: null
  };
};
