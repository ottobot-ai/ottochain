import { batchSign, dropNulls, generateKeyPair } from '@ottochain/sdk';

const keyPair = generateKeyPair();
console.log('E2E Test - Address:', keyPair.address);

const message = {
  CreateStateMachine: {
    fiberId: 'compare-test-' + Date.now(),
    definition: { 
      metadata: { name: 'Market', version: '1.0.0' }, 
      states: { PROPOSED: { id: 'PROPOSED', isFinal: false } }, 
      transitions: [], 
      initialState: 'PROPOSED' 
    },
    initialData: { status: 'PROPOSED', creator: keyPair.address },
    parentFiberId: null,
  },
};

async function main() {
  // metakit >= 1.8 hashes over null-dropped canonical bytes (content-hash rule):
  // drop null object fields before signing so the node re-derives the same bytes.
  const cleaned = dropNulls(message);
  const signed = await batchSign(cleaned, [keyPair.privateKey], { isDataUpdate: true });

  console.log('\n=== E2E Signed Structure ===');
  console.log('Keys:', Object.keys(signed));
  console.log('Value keys:', Object.keys(signed.value));
  console.log('Proofs length:', signed.proofs.length);
  console.log('Proof[0] keys:', Object.keys(signed.proofs[0]));
  console.log('Proof[0].id length:', signed.proofs[0].id.length);
  console.log('Proof[0].signature length:', signed.proofs[0].signature.length);
  console.log('\nFull payload (first 500 chars):');
  console.log(JSON.stringify(signed).substring(0, 500));
}

main();
