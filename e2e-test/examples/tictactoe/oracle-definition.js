/**
 * Tic-Tac-Toe Oracle Definition
 *
 * A JsonLogic-based oracle that manages game state for tic-tac-toe.
 * Supports: initialize, makeMove, checkWinner, getBoard, resetGame, cancelGame
 */
module.exports = function(context) {
  // context provides: { wallets, session }
  // For oracle definition, we don't need dynamic values - it's pure JsonLogic

  return {
    scriptProgram: {
      if: [
        // Initialize method
        { '===': [{ var: 'method' }, 'initialize'] },
        {
          _state: {
            board: [null, null, null, null, null, null, null, null, null],
            currentTurn: 'X',
            moveCount: 0,
            status: 'InProgress',
            playerX: { var: 'args.playerX' },
            playerO: { var: 'args.playerO' },
            gameId: { var: 'args.gameId' },
            moveHistory: [],
            winner: null,
            cancelledBy: null,
            cancelReason: null
          },
          _result: {
            status: 'initialized',
            board: [null, null, null, null, null, null, null, null, null]
          }
        },

        // makeMove method
        { '===': [{ var: 'method' }, 'makeMove'] },
        {
          if: [
            { '!==': [{ var: 'state.status' }, 'InProgress'] },
            { _result: { valid: false, error: 'Game is not in progress' } },
            {
              if: [
                { '!==': [{ var: 'args.player' }, { var: 'state.currentTurn' }] },
                { _result: { valid: false, error: 'Not your turn' } },
                {
                  if: [
                    { or: [{ '<': [{ var: 'args.cell' }, 0] }, { '>': [{ var: 'args.cell' }, 8] }] },
                    { _result: { valid: false, error: 'Cell out of bounds' } },
                    {
                      if: [
                        { '!!': [{ var: { cat: ['state.board.', { var: 'args.cell' }] } }] },
                        { _result: { valid: false, error: 'Cell already occupied' } },
                        {
                          _state: {
                            board: {
                              merge: [
                                { slice: [{ var: 'state.board' }, 0, { var: 'args.cell' }] },
                                [{ var: 'args.player' }],
                                { slice: [{ var: 'state.board' }, { '+': [{ var: 'args.cell' }, 1] }] }
                              ]
                            },
                            moveCount: { '+': [{ var: 'state.moveCount' }, 1] },
                            currentTurn: { if: [{ '===': [{ var: 'args.player' }, 'X'] }, 'O', 'X'] },
                            moveHistory: {
                              merge: [
                                { var: 'state.moveHistory' },
                                [{ player: { var: 'args.player' }, cell: { var: 'args.cell' } }]
                              ]
                            },
                            status: {
                              if: [
                                { or: winConditions() },
                                'Won',
                                {
                                  if: [
                                    { '===': [{ '+': [{ var: 'state.moveCount' }, 1] }, 9] },
                                    'Draw',
                                    'InProgress'
                                  ]
                                }
                              ]
                            },
                            winner: {
                              if: [
                                { or: winConditions() },
                                { var: 'args.player' },
                                null
                              ]
                            },
                            playerX: { var: 'state.playerX' },
                            playerO: { var: 'state.playerO' },
                            gameId: { var: 'state.gameId' },
                            cancelledBy: null,
                            cancelReason: null
                          },
                          _result: {
                            valid: true,
                            status: { var: '_state.status' },
                            winner: { var: '_state.winner' }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        },

        // checkWinner method
        { '===': [{ var: 'method' }, 'checkWinner'] },
        {
          _result: {
            status: { var: 'state.status' },
            winner: { var: 'state.winner' }
          }
        },

        // getBoard method
        { '===': [{ var: 'method' }, 'getBoard'] },
        {
          _result: {
            board: { var: 'state.board' },
            currentTurn: { var: 'state.currentTurn' },
            moveCount: { var: 'state.moveCount' }
          }
        },

        // resetGame method
        { '===': [{ var: 'method' }, 'resetGame'] },
        {
          _state: {
            board: [null, null, null, null, null, null, null, null, null],
            currentTurn: 'X',
            moveCount: 0,
            status: 'InProgress',
            playerX: { var: 'state.playerX' },
            playerO: { var: 'state.playerO' },
            gameId: { var: 'state.gameId' },
            moveHistory: [],
            winner: null,
            cancelledBy: null,
            cancelReason: null
          },
          _result: {
            status: 'reset',
            board: [null, null, null, null, null, null, null, null, null],
            message: 'Board reset for new round'
          }
        },

        // cancelGame method
        { '===': [{ var: 'method' }, 'cancelGame'] },
        {
          _state: {
            merge: [
              { var: 'state' },
              {
                status: 'Cancelled',
                cancelledBy: { var: 'args.requestedBy' },
                cancelReason: { var: 'args.reason' }
              }
            ]
          },
          _result: {
            status: 'Cancelled',
            message: 'Game cancelled'
          }
        },

        // Default: unknown method
        { _result: { error: 'Unknown method' } }
      ]
    },
    initialState: {
      board: [null, null, null, null, null, null, null, null, null],
      currentTurn: 'X',
      moveCount: 0,
      status: 'NotInitialized',
      playerX: null,
      playerO: null,
      gameId: null,
      moveHistory: [],
      winner: null,
      cancelledBy: null,
      cancelReason: null
    },
    accessControl: {
      Public: {}
    }
  };
};

/**
 * Generate win condition checks for all 8 winning lines.
 * Each condition checks if the current player has all three cells in a line,
 * accounting for the cell being placed in the current move.
 */
function winConditions() {
  const lines = [
    [0, 1, 2], // top row
    [3, 4, 5], // middle row
    [6, 7, 8], // bottom row
    [0, 3, 6], // left column
    [1, 4, 7], // middle column
    [2, 5, 8], // right column
    [0, 4, 8], // diagonal
    [2, 4, 6]  // anti-diagonal
  ];

  return lines.map(([a, b, c]) => ({
    and: [
      cellMatchesPlayer(a),
      cellMatchesPlayer(b),
      cellMatchesPlayer(c)
    ]
  }));
}

/**
 * Check if a cell matches the current player.
 * If the cell is the one being placed, use the player from args.
 * Otherwise, check the existing board state.
 */
function cellMatchesPlayer(cell) {
  return {
    '===': [
      {
        if: [
          { '===': [{ var: 'args.cell' }, cell] },
          { var: 'args.player' },
          { var: `state.board.${cell}` }
        ]
      },
      { var: 'args.player' }
    ]
  };
}
