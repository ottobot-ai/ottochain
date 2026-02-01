export default (_context: Record<string, unknown>) => ({
  eventName: 'submit',
  payload: {
    timestamp: new Date().toISOString(),
  },
});
