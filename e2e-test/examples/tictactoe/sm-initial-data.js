/**
 * Tic-Tac-Toe State Machine Initial Data
 *
 * Sets up the initial state with oracle CID reference.
 */

const { WELL_KNOWN_ORACLE_CID } = require('./sm-definition.js');

module.exports = function(context) {
  // context provides: { wallets, session }
  const oracleCid = context?.session?.oracleCid || WELL_KNOWN_ORACLE_CID;

  return {
    oracleCid: oracleCid,
    roundCount: 0
  };
};
