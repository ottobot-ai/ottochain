/**
 * Tic-Tac-Toe Example Configuration
 *
 * Complete tic-tac-toe implementation demonstrating the oracle-centric
 * architecture pattern where the oracle holds game state and the state
 * machine orchestrates the lifecycle.
 */

const { WELL_KNOWN_ORACLE_CID } = require('./sm-definition.js');

module.exports = {
  name: 'Tic-Tac-Toe Game',
  description: 'Complete tic-tac-toe implementation demonstrating the oracle-centric architecture pattern where the oracle holds game state and the state machine orchestrates the lifecycle',
  type: 'combined',

  oracle: {
    definition: 'oracle-definition.js',
    methods: [
      {
        name: 'initialize',
        description: 'Initialize game with two players',
        args: { playerX: 'DAG...', playerO: 'DAG...', gameId: 'uuid' }
      },
      {
        name: 'makeMove',
        description: 'Make a move on the board (validates turn, bounds, occupancy)',
        args: { player: 'X or O', cell: '0-8' }
      },
      {
        name: 'checkWinner',
        description: 'Check current game status and winner (read-only)',
        args: null
      },
      {
        name: 'getBoard',
        description: 'Get current board state (read-only)',
        args: null
      },
      {
        name: 'resetGame',
        description: 'Reset board for a new round (keeps players)',
        args: null
      },
      {
        name: 'cancelGame',
        description: 'Cancel the game',
        args: { requestedBy: 'DAG...', reason: 'string' }
      }
    ]
  },

  stateMachine: {
    definition: 'sm-definition.js',
    initialData: 'sm-initial-data.js',
    events: [
      {
        name: 'start_game',
        description: 'Start the game with two players',
        file: 'event-start-game.js',
        from: 'setup',
        to: 'playing'
      },
      {
        name: 'make_move',
        description: 'Make a move (stays in playing or goes to finished)',
        file: 'event-move.js',
        from: 'playing',
        to: 'playing/finished'
      },
      {
        name: 'reset_board',
        description: 'Reset the board for another round',
        file: 'event-reset.js',
        from: 'playing',
        to: 'playing'
      },
      {
        name: 'cancel_game',
        description: 'Cancel the game',
        file: 'event-cancel.js',
        from: 'setup/playing',
        to: 'cancelled'
      }
    ]
  },

  // Well-known oracle CID for state machine dependencies
  oracleCid: WELL_KNOWN_ORACLE_CID,

  testFlows: [
    {
      name: 'X wins with top row',
      description: 'Play a game where X wins by completing the top row (cells 0, 1, 2)',
      steps: [
        { action: 'createOracle', definition: 'oracle-definition.js' },
        { action: 'createStateMachine', definition: 'sm-definition.js', initialData: 'sm-initial-data.js' },
        { action: 'processEvent', event: 'event-start-game.js' },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 0 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'O', cell: 3 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 1 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'O', cell: 4 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 2 } }
      ]
    },
    {
      name: 'O wins with left column',
      description: 'Play a game where O wins by completing the left column (cells 0, 3, 6)',
      steps: [
        { action: 'createOracle', definition: 'oracle-definition.js' },
        { action: 'createStateMachine', definition: 'sm-definition.js', initialData: 'sm-initial-data.js' },
        { action: 'processEvent', event: 'event-start-game.js' },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 1 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'O', cell: 0 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 2 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'O', cell: 3 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 8 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'O', cell: 6 } }
      ]
    },
    {
      name: 'Draw game',
      description: 'Play a game that ends in a draw',
      steps: [
        { action: 'createOracle', definition: 'oracle-definition.js' },
        { action: 'createStateMachine', definition: 'sm-definition.js', initialData: 'sm-initial-data.js' },
        { action: 'processEvent', event: 'event-start-game.js' },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 0 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'O', cell: 4 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 8 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'O', cell: 2 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 6 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'O', cell: 3 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 5 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'O', cell: 7 } },
        { action: 'processEvent', event: 'event-move.js', eventData: { player: 'X', cell: 1 } }
      ]
    }
  ]
};
