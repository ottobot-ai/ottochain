import crypto from 'crypto';

export default (context: Record<string, unknown>) => {
  const session = context?.session as { oracleFiberId?: string } | undefined;
  const oracleFiberId = session?.oracleFiberId || crypto.randomUUID();

  return {
    oracleFiberId,
    roundCount: 0,
  };
};
