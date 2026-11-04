import type { Event, EventStore } from '../domain/outbox.ts';
export class OutboxWorker {
  private store: EventStore;
  private deliver: (event: Event) => Promise<void>;
  private now: () => Date;
  constructor(store: EventStore, deliver: (event: Event) => Promise<void>, now = () => new Date()) { this.store = store; this.deliver = deliver; this.now = now; }
  async once() {
    const event = await this.store.claim(this.now());
    if (!event) return false;
    try { await this.deliver(event); await this.store.acknowledge(event); }
    catch { await this.store.fail(event,this.now()); }
    return true;
  }
}
