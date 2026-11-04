export type Event = { id: string; action: string; object_id: string; payload: Record<string, unknown>; lease_token: string; attempts: number };
export interface EventStore {
  claim(now: Date): Promise<Event | null>;
  acknowledge(event: Event): Promise<boolean>;
  fail(event: Event, now: Date): Promise<void>;
}
