import { randomUUID } from 'node:crypto';
import type { Event, EventStore } from '../domain/outbox.ts';
import { Postgres, SYSTEM } from './postgres.ts';

export class PostgresEvents implements EventStore {
  private repo: Postgres;
  constructor(repo: Postgres) { this.repo = repo; }
  async claim(now: Date): Promise<Event | null> {
    return await this.repo.one("WITH candidate AS (SELECT id FROM outbox_events WHERE (status='pending' AND available_at<=$1) OR (status='leased' AND lease_until<=$1) ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE outbox_events e SET status='leased',lease_token=$2,lease_until=$3,updated_at=CURRENT_TIMESTAMP FROM candidate c WHERE e.id=c.id RETURNING e.*", [now,randomUUID(),new Date(now.getTime()+30000)]) as Event | null;
  }
  async acknowledge(event: Event) { return !!await this.repo.one("UPDATE outbox_events SET status='completed',lease_until=NULL,lease_token=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=$1 AND status='leased' AND lease_token=$2 RETURNING id",[event.id,event.lease_token]); }
  async fail(event: Event, now: Date) {
    const terminal = event.attempts >= 5;
    const next = new Date(now.getTime()+[1000,2000,4000,8000,16000][Math.min(event.attempts,4)]);
    const row = await this.repo.one("UPDATE outbox_events SET status=$3,attempts=$4,available_at=$5,lease_until=NULL,lease_token=NULL,last_error='DELIVERY_FAILED',updated_at=CURRENT_TIMESTAMP WHERE id=$1 AND status='leased' AND lease_token=$2 RETURNING id",[event.id,event.lease_token,terminal?'failed':'pending',terminal?5:event.attempts+1,next]);
    if (row && terminal) console.error(JSON.stringify({ timestamp:now.toISOString(),level:'error',module:'outbox',requestId:event.id,eventId:event.id,code:'OUTBOX_RETRIES_EXHAUSTED',action:'inspect failed event and replay after correcting consumer' }));
  }
  async internalDelivery(event: Event) {
    // Internal effects and consumer receipt commit together. External adapters must honor event.id independently.
    await this.repo.transaction(async r => {
      await r.lockKey(`consume:${event.id}`);
      const repo = r as Postgres;
      if (await repo.one('SELECT id FROM event_receipts WHERE event_id=$1',[event.id])) return;
      if (!['attempt.submitted','attempt.graded'].includes(event.action)) throw new Error('No registered event consumer');
      await repo.rows('INSERT INTO event_receipts(id,event_id,created_by) VALUES($1,$2,$3)',[randomUUID(),event.id,SYSTEM]);
      await r.audit(SYSTEM,'event.delivered',event.object_id,event.id);
    });
  }
}
