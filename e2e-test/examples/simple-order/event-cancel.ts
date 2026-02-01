export default (_context: Record<string, unknown>) => ({
  eventName: 'cancel',
  payload: {
    reason: 'Customer requested cancellation',
  },
});
