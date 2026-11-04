import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture } from '../../database.ts';
import { SYSTEM } from '../../../src/infrastructure/postgres.ts';
import { PostgresEvents } from '../../../src/infrastructure/outbox.ts';
import { OutboxWorker } from '../../../src/application/outbox.ts';

test('DT04-N/F: rolled back events never deliver; expired leases retry without duplicate effects; stale claimant cannot ack', async () => {
  const f = await fixture(); const events = new PostgresEvents(f.repo);
  try {
    const objectId = randomUUID();
    await assert.rejects(() => f.repo.transaction(async r => { await r.enqueue(SYSTEM,'attempt.submitted',objectId); throw new Error('rollback'); }));
    assert.equal(await events.claim(new Date('2030-01-01')),null);
    await f.repo.enqueue(SYSTEM,'attempt.submitted',objectId);
    const initial = new Date('2030-01-01T00:00:00Z');
    await f.repo.rows('UPDATE outbox_events SET available_at=$1',[initial]);
    const first = (await events.claim(initial))!;
    assert.ok(first); assert.equal(await events.claim(initial),null);
    await events.internalDelivery(first); // Process dies here before acknowledging.
    const second = (await events.claim(new Date(initial.getTime()+30000)))!;
    assert.equal(second.id,first.id); assert.notEqual(second.lease_token,first.lease_token);
    assert.equal(await events.acknowledge(first),false);
    await events.internalDelivery(second);
    assert.equal(await events.acknowledge(second),true);
    assert.equal((await f.repo.rows('SELECT * FROM event_receipts')).length,1);
    assert.equal((await f.repo.rows("SELECT * FROM audit_events WHERE action='event.delivered'")).length,1);
  } finally { await f.close(); }
});

test('DT04-N: bounded 1/2/4/8/16 second retries end in failed queue', async () => {
  const f = await fixture(); const events = new PostgresEvents(f.repo); let calls=0;
  let clock = new Date('2030-01-01T00:00:00Z');
  try {
    await f.repo.enqueue(SYSTEM,'attempt.submitted',randomUUID());
    await f.repo.rows('UPDATE outbox_events SET available_at=$1',[clock]);
    const worker = new OutboxWorker(events,async () => { calls++; throw new Error('private body must not be logged'); },() => clock);
    for (const delay of [1000,2000,4000,8000,16000]) {
      assert.equal(await worker.once(),true);
      assert.equal(await worker.once(),false);
      clock = new Date(clock.getTime()+delay);
    }
    assert.equal(await worker.once(),true); assert.equal(await worker.once(),false);
    assert.equal(calls,6);
    const row = (await f.repo.rows('SELECT * FROM outbox_events'))[0];
    assert.equal(row.status,'failed'); assert.equal(row.attempts,5); assert.equal(row.last_error,'DELIVERY_FAILED');
  } finally { await f.close(); }
});
