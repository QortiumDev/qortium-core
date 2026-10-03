// Public fixtures: Home's account seed semantics plus uppercase XMR ticker mixing.
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
const { fixtures } = JSON.parse(readFileSync(new URL('../../src/test/resources/monero/derivation-v1.json', import.meta.url)));
const hash = (algorithm, bytes) => createHash(algorithm).update(bytes).digest();
for (const fixture of fixtures) {
  const master = Buffer.from(fixture.masterSeed, 'hex');
  const nonce = Buffer.alloc(4); nonce.writeUInt32BE(fixture.nonce);
  const material = Buffer.concat([nonce, master, nonce]);
  const account = fixture.walletVersion === 1 ? master : hash('sha512', Buffer.concat([hash('sha512', material), material])).subarray(0, 32);
  const reversed = Buffer.from(account).reverse();
  const indicator = hash('sha256', Buffer.concat([reversed, Buffer.from('XMR', 'utf8')]));
  const coin = hash('sha512', Buffer.concat([reversed, indicator])).subarray(0, 32);
  assert.equal(coin.toString('hex'), fixture.coinSeed);
}
console.log(`PASS ${fixtures.length} Node/Home-semantics XMR coin-seed fixtures`);
