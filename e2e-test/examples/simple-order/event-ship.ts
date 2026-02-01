export default (_context: Record<string, unknown>) => ({
  eventName: 'ship',
  payload: {
    trackingNumber: 'TRACK-12345',
    carrier: 'FedEx',
    shippedAt: new Date().toISOString(),
  },
});
