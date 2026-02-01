import crypto from 'crypto';

export default (context: Record<string, unknown>) => {
  const session = context?.session as { oracleCid?: string } | undefined;
  const oracleCid = session?.oracleCid || crypto.randomUUID();

  return {
    oracleCid,
    roundCount: 0,
  };
};
