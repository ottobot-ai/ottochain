import crypto from 'crypto';

/**
 * Tic-Tac-Toe State Machine Definition
 *
 * Orchestrates the game lifecycle: setup -> playing -> finished/cancelled
 * Coordinates with the oracle via _oracleCall effects.
 *
 * The oracle CID is injected dynamically from context.session.oracleCid
 * so each test run uses a fresh oracle.
 */
export default (context: Record<string, unknown>) => {
  const session = context?.session as { oracleCid?: string } | undefined;
  const oracleCid = session?.oracleCid || crypto.randomUUID();

  return {
    states: {
      setup: {
        id: { value: 'setup' },
        isFinal: false,
        metadata: null,
      },
      playing: {
        id: { value: 'playing' },
        isFinal: false,
        metadata: null,
      },
      finished: {
        id: { value: 'finished' },
        isFinal: true,
        metadata: null,
      },
      cancelled: {
        id: { value: 'cancelled' },
        isFinal: true,
        metadata: null,
      },
    },
    initialState: { value: 'setup' },
    transitions: [
      // setup -> playing (start_game)
      {
        from: { value: 'setup' },
        to: { value: 'playing' },
        eventName: 'start_game',
        guard: {
          and: [
            { '!!': [{ var: 'event.playerX' }] },
            { '!!': [{ var: 'event.playerO' }] },
            { '!!': [{ var: 'event.gameId' }] },
          ],
        },
        effect: {
          _oracleCall: {
            cid: { var: 'state.oracleCid' },
            method: 'initialize',
            args: {
              playerX: { var: 'event.playerX' },
              playerO: { var: 'event.playerO' },
              gameId: { var: 'event.gameId' },
            },
          },
          gameId: { var: 'event.gameId' },
          playerX: { var: 'event.playerX' },
          playerO: { var: 'event.playerO' },
          status: 'initialized',
        },
        dependencies: [],
      },

      // playing -> playing (make_move, game continues)
      {
        from: { value: 'playing' },
        to: { value: 'playing' },
        eventName: 'make_move',
        guard: {
          '===': [{ var: `scriptOracles.${oracleCid}.state.status` }, 'InProgress'],
        },
        effect: {
          _oracleCall: {
            cid: { var: 'state.oracleCid' },
            method: 'makeMove',
            args: {
              player: { var: 'event.player' },
              cell: { var: 'event.cell' },
            },
          },
          lastMove: {
            player: { var: 'event.player' },
            cell: { var: 'event.cell' },
          },
        },
        dependencies: [oracleCid],
      },

      // playing -> finished (make_move, game ends with win/draw)
      {
        from: { value: 'playing' },
        to: { value: 'finished' },
        eventName: 'make_move',
        guard: {
          or: [
            { '===': [{ var: `scriptOracles.${oracleCid}.state.status` }, 'Won'] },
            { '===': [{ var: `scriptOracles.${oracleCid}.state.status` }, 'Draw'] },
          ],
        },
        effect: {
          _oracleCall: {
            cid: { var: 'state.oracleCid' },
            method: 'makeMove',
            args: {
              player: { var: 'event.player' },
              cell: { var: 'event.cell' },
            },
          },
          finalStatus: { var: `scriptOracles.${oracleCid}.state.status` },
          winner: { var: `scriptOracles.${oracleCid}.state.winner` },
          finalBoard: { var: `scriptOracles.${oracleCid}.state.board` },
          _emit: [
            {
              name: 'game_completed',
              data: {
                gameId: { var: 'state.gameId' },
                winner: { var: `scriptOracles.${oracleCid}.state.winner` },
                status: { var: `scriptOracles.${oracleCid}.state.status` },
                moveCount: { var: `scriptOracles.${oracleCid}.state.moveCount` },
              },
            },
          ],
        },
        dependencies: [oracleCid],
      },

      // playing -> playing (reset_board, start new round)
      {
        from: { value: 'playing' },
        to: { value: 'playing' },
        eventName: 'reset_board',
        guard: {
          or: [
            { '===': [{ var: `scriptOracles.${oracleCid}.state.status` }, 'Won'] },
            { '===': [{ var: `scriptOracles.${oracleCid}.state.status` }, 'Draw'] },
          ],
        },
        effect: {
          _oracleCall: {
            cid: { var: 'state.oracleCid' },
            method: 'resetGame',
            args: {},
          },
          roundCount: { '+': [{ var: 'state.roundCount' }, 1] },
        },
        dependencies: [oracleCid],
      },

      // playing -> cancelled (cancel_game)
      {
        from: { value: 'playing' },
        to: { value: 'cancelled' },
        eventName: 'cancel_game',
        guard: { '==': [1, 1] },
        effect: {
          _oracleCall: {
            cid: { var: 'state.oracleCid' },
            method: 'cancelGame',
            args: {
              requestedBy: { var: 'event.requestedBy' },
              reason: { var: 'event.reason' },
            },
          },
          cancelledBy: { var: 'event.requestedBy' },
          cancelReason: { var: 'event.reason' },
        },
        dependencies: [],
      },

      // setup -> cancelled (cancel_game before start)
      {
        from: { value: 'setup' },
        to: { value: 'cancelled' },
        eventName: 'cancel_game',
        guard: { '==': [1, 1] },
        effect: {
          cancelledBy: { var: 'event.requestedBy' },
          cancelReason: { var: 'event.reason' },
        },
        dependencies: [],
      },
    ],
    metadata: {
      name: 'TicTacToeLifecycle',
      description: 'Game lifecycle orchestrator for tic-tac-toe',
    },
  };
};
