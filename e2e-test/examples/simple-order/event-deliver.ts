export default (_context: Record<string, unknown>) => ({
  eventName: 'deliver',
  payload: {
    timestamp: new Date().toISOString(),
  },
});
