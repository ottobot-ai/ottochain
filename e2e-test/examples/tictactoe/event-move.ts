export default (context: Record<string, unknown>) => {
  const eventData = context.eventData as Record<string, unknown> | undefined;

  return {
    eventName: 'make_move',
    payload: {
      player: (eventData?.player as string) || 'X',
      cell: (eventData?.cell as number) ?? 0,
    },
  };
};
