export default (_context: Record<string, unknown>) => ({
  eventName: 'confirm',
  payload: {
    confirmedAt: new Date().toISOString(),
    paymentMethod: 'credit_card',
  },
});
