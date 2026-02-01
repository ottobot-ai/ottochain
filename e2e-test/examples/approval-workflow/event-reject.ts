export default (_context: Record<string, unknown>) => ({
  eventName: 'reject',
  payload: {
    approver: 'manager@example.com',
    reason: 'Budget constraints',
  },
});
