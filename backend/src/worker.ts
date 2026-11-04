import { config } from './config.ts';
import { database } from './infrastructure/database.ts';
import { Postgres } from './infrastructure/postgres.ts';
import { PostgresEvents } from './infrastructure/outbox.ts';
import { Exams } from './application/exams.ts';
import { OutboxWorker } from './application/outbox.ts';
import { randomUUID } from 'node:crypto';
import { setTimeout } from 'node:timers/promises';

const settings=config(process.env),db=database(settings.databaseUrl);
const repo=new Postgres(db.pool),events=new PostgresEvents(repo),exams=new Exams(repo);
const worker=new OutboxWorker(events,event=>events.internalDelivery(event));
let stop=false;
for (const signal of ['SIGINT','SIGTERM']) process.once(signal,()=>{stop=true;});
try {
  while (!stop) {
    try {
      await exams.freezeDue((await repo.due(new Date())).map(row=>row.id),randomUUID());
      if (settings.outbox) await worker.once();
    } catch {
      console.error(JSON.stringify({timestamp:new Date().toISOString(),level:'error',module:'worker',requestId:randomUUID(),code:'DEP_UNAVAILABLE',action:'check database connectivity; events remain durable'}));
    }
    if (!stop) await setTimeout(1000);
  }
} finally { await db.close(); }
