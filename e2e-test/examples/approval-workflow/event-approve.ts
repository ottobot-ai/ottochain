export default (_context: Record<string, unknown>) => ({
  eventName: 'approve',
  payload: {
    approver: 'manager@example.com',
  },
});
