import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture, testTarget } from '../../database.ts';
import { seedExam } from '../../../src/infrastructure/seed.ts';
import { SYSTEM } from '../../../src/infrastructure/postgres.ts';
import { migrate } from '../../../src/infrastructure/migrate.ts';
import { Exams } from '../../../src/application/exams.ts';
import { buildServer } from '../../../src/interfaces/http.ts';
import { Failure } from '../../../src/domain/contracts.ts';
import type { Principal, Row } from '../../../src/domain/contracts.ts';
import { headers } from '../../support.ts';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export async function examFixture() {
  const db = await fixture();
  try {
    const content = await seedExam(db.pool);
    const a = randomUUID(), b = randomUUID();
    for (const [id,name] of [[a,'synthetic_a'],[b,'synthetic_b']]) await db.repo.transaction(r => r.createUser({ id,username:name,role:'student',gradeId:content.gradeId,passwordHash:'synthetic-hash-not-used-for-login' }));
    let instant = new Date('2026-06-01T00:00:00Z');
    const actor: Principal = { id:a,role:'student',sessionId:randomUUID() };
    const other: Principal = { id:b,role:'student',sessionId:randomUUID() };
    const exams = new Exams(db.repo, () => instant);
    return { ...db, ...content, actor, other, exams, time(value: string) { instant = new Date(value); }, async start(type = 'fixed', key = randomUUID()) { return exams.start(actor,content.papers[type],false,key,`/api/v1/papers/${content.papers[type]}/attempts`,'request-start'); } };
  } catch (error) { await db.close(); throw error; }
}

test('DT01/DT03/DT05: real PostgreSQL migrations rerun, detect history edits and roll back on a disposable schema', async () => {
  const db = await fixture();
  const directory = await mkdtemp(path.join(tmpdir(),'exam-migration-'));
  try {
    await migrate(db.pool); assert.equal((await db.repo.rows('SELECT * FROM schema_migrations')).length,2);
    assert.equal((await db.repo.userById(SYSTEM))!.role,'system');
    assert.equal((await db.repo.rows('SHOW timezone'))[0].TimeZone,'UTC');
    const dictionary=await readFile(new URL('../../../../docs/data/dictionary.md',import.meta.url),'utf8');
    const columns=await db.repo.rows("SELECT table_name,column_name,data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name<>'schema_migrations'");
    for(const column of columns){
      const entry=dictionary.split('\n').find(line=>line.startsWith(`| ${column.table_name} |`));
      assert.ok(entry,`missing dictionary table ${column.table_name}`);
      assert.ok(['id','created_at','updated_at','created_by'].includes(column.column_name)||entry.includes(column.column_name),`missing dictionary field ${column.table_name}.${column.column_name}`);
      if(column.column_name==='id')assert.equal(column.data_type,'uuid');
      if(['created_at','updated_at'].includes(column.column_name))assert.equal(column.data_type,'timestamp with time zone');
    }
    const up = await readFile(new URL('../../../migrations/001_foundation.up.sql',import.meta.url),'utf8');
    const down = await readFile(new URL('../../../migrations/001_foundation.down.sql',import.meta.url),'utf8');
    await writeFile(path.join(directory,'001_foundation.up.sql'), up + '\n-- forbidden history rewrite\n');
    await writeFile(path.join(directory,'001_foundation.down.sql'), down);
    await assert.rejects(() => migrate(db.pool,'up',pathToFileURL(directory + '/')), /摘要/);
    await db.pool.query(up); // The migration SQL itself is conditional and repeatable.
    await migrate(db.pool,'down');
    assert.equal((await db.repo.rows("SELECT to_regclass('account_guards') AS value"))[0].value,null);
    assert.ok(await db.repo.userById(SYSTEM)); // 002 rollback preserves old application reads and all accounts.
    await assert.rejects(()=>db.repo.transaction(r=>r.lockAdministrators()),(error:Failure)=>error.code==='DEP_UNAVAILABLE');
    await migrate(db.pool,'down');
    assert.equal((await db.repo.rows("SELECT to_regclass('users') AS value"))[0].value,null);
    await migrate(db.pool); assert.ok(await db.repo.userById(SYSTEM));
    await db.repo.lockAdministrators();
  } finally { await db.close(); await rm(directory,{ recursive:true,force:true }); }
});

test('CT06-N/F: real PostgreSQL equal-key ordering across pages and fixed dataset total', async () => {
  const f = await examFixture();
  try {
    for (let i=0;i<5;i++) await f.pool.query("INSERT INTO papers(id,title,type,status,duration_seconds,created_at,created_by) VALUES($1,'同名','fixed','published',60,'2026-01-01',$2)",[randomUUID(),SYSTEM]);
    await f.pool.query("UPDATE papers SET created_at='2026-01-01'");
    const first = await f.repo.list('papers',f.actor.id,{ page:1,pageSize:3,sort:'createdAt',direction:'asc' });
    const second = await f.repo.list('papers',f.actor.id,{ page:2,pageSize:3,sort:'createdAt',direction:'asc' });
    const third = await f.repo.list('papers',f.actor.id,{ page:3,pageSize:3,sort:'createdAt',direction:'asc' });
    const ids = [...first.items,...second.items,...third.items].map(r => r.id);
    assert.equal(ids.length,7); assert.equal(new Set(ids).size,7); assert.deepEqual(ids,[...ids].sort()); assert.equal(first.total,7);
    await assert.rejects(() => f.repo.list('papers',f.actor.id,{ page:1,pageSize:20,sort:'password_hash',direction:'asc' }), (e:Failure) => e.code==='PARAM_INVALID');
  } finally { await f.close(); }
});

test('API04/CT05: concurrent task starts, same-key submissions, changed payload and stale versions', async () => {
  const f = await examFixture();
  try {
    const task = randomUUID();
    await f.pool.query("INSERT INTO tasks(id,paper_id,paper_version_id,grade_id,status,start_at,end_at,created_by) VALUES($1,$2,$3,$4,'published','2026-01-01','2027-01-01',$5)",[task,f.papers.task,f.versions.task,f.gradeId,SYSTEM]);
    await f.pool.query('INSERT INTO task_recipients(id,task_id,owner_id,created_by) VALUES($1,$2,$3,$4)',[randomUUID(),task,f.actor.id,SYSTEM]);
    const starts = await Promise.all(Array.from({length:8},() => f.exams.start(f.actor,task,true,randomUUID(),`/api/v1/tasks/${task}/attempts`,'task-request')));
    assert.equal(new Set(starts.map(r => r.id)).size,1);
    assert.equal((await f.repo.rows('SELECT * FROM attempts WHERE task_id=$1',[task])).length,1);
    await assert.rejects(() => f.exams.start(f.other,task,true,randomUUID(),`/api/v1/tasks/${task}/attempts`,'forbidden'),(e:Failure) => e.code==='RESOURCE_NOT_FOUND');
    const a = starts[0]; const item = a.snapshot.items[0]; const body = { version:a.version,answers:[{itemId:item.id,text:'键盘'}] };
    const results = await Promise.all(Array.from({length:8},() => f.exams.answers(f.actor,a.id,body,'same-submission',true,'submit-request')));
    for (const result of results) assert.deepEqual(JSON.parse(JSON.stringify(result)),JSON.parse(JSON.stringify(results[0])));
    assert.equal((await f.repo.attempt(a.id))!.version,2);
    assert.equal((await f.repo.rows("SELECT * FROM audit_events WHERE object_id=$1 AND action='attempt.submitted'",[a.id])).length,1);
    assert.equal((await f.repo.rows("SELECT * FROM outbox_events WHERE object_id=$1 AND action='attempt.submitted'",[a.id])).length,1);
    await assert.rejects(() => f.exams.answers(f.actor,a.id,{...body,answers:[]},'same-submission',true,'r'),(e:Failure) => e.code==='BIZ_IDEMPOTENCY_CONFLICT');
    const running = await f.start();
    await f.exams.answers(f.actor,running.id,{version:1,answers:[]},'save-1',false,'r');
    await assert.rejects(() => f.exams.answers(f.actor,running.id,{version:1,answers:[]},'save-2',false,'r'),(e:Failure) => e.code==='BIZ_VERSION_CONFLICT');
  } finally { await f.close(); }
});

test('API05-N2/CT02-N2: expired GETs are read-only; late writes freeze only saved drafts once', async () => {
  const f = await examFixture();
  const security = { async authenticate() { return f.actor; },async authorize() {},async revokeUser() {} };
  const app = buildServer({ origins:['https://exam.test'],rateLimit:1000,security,exams:f.exams });
  try {
    const attempt = await f.start(); const item = attempt.snapshot.items[0];
    await f.exams.answers(f.actor,attempt.id,{version:1,answers:[{itemId:item.id,text:'键盘'}]},'save',false,'r');
    const before = await f.repo.attempt(attempt.id);
    const audits = (await f.repo.rows('SELECT * FROM audit_events')).length;
    f.time('2026-06-01T00:10:00Z');
    const detail = (await app.inject({ url:`/api/v1/attempts/${attempt.id}`,headers })).json();
    assert.equal(detail.code,'OK'); assert.equal(detail.data.status,'submitted'); assert.equal(detail.data.version,2);
    assert.ok(detail.data.snapshot.items[0].referenceAnswer);
    const list = (await app.inject({ url:'/api/v1/attempts?status=submitted',headers })).json().data;
    assert.deepEqual(list.items.map((r:Row) => r.id),[attempt.id]);
    assert.deepEqual(await f.repo.attempt(attempt.id),before);
    assert.equal((await f.repo.rows('SELECT * FROM audit_events')).length,audits);
    assert.equal((await f.repo.rows('SELECT * FROM outbox_events')).length,0);
    await assert.rejects(() => f.exams.answers(f.actor,attempt.id,{version:2,answers:[{itemId:item.id,text:'显示器'}]},'late',true,'r'),(e:Failure) => e.code==='BIZ_DEADLINE_REACHED');
    assert.equal((await f.repo.attempt(attempt.id))!.status,'submitted');
    assert.deepEqual(await f.repo.answers(attempt.id),[{itemId:item.id,text:'键盘'}]);
    await f.exams.freezeDue([attempt.id,attempt.id],'scanner');
    assert.equal((await f.repo.rows("SELECT * FROM audit_events WHERE action='attempt.expired' AND object_id=$1",[attempt.id])).length,1);
  } finally { await app.close(); await f.close(); }
});

test('API06/DT04: exact partial scores, deduplicated mistakes, repeat confirmation and failure rollback', async () => {
  const f = await examFixture();
  try {
    const attempt = await f.start();
    const submitted = await f.exams.answers(f.actor,attempt.id,{version:1,answers:[]},'submit',true,'r');
    const scores = submitted.snapshot.items.map((item:Row,i:number) => ({itemId:item.id,score:i===0?'0.10':i===1?'0.20':'20.00'}));
    const result = await f.exams.grade(f.actor,attempt.id,{version:2,scores},'grade','r');
    assert.equal(result.total,'60.30'); assert.equal((await f.repo.rows('SELECT * FROM gradings')).length,1);
    assert.equal((await f.repo.rows('SELECT * FROM mistakes')).length,2);
    assert.deepEqual(JSON.parse(JSON.stringify(await f.exams.grade(f.actor,attempt.id,{version:2,scores},'grade','r'))),JSON.parse(JSON.stringify(result)));
    const second = await f.start(); await f.exams.answers(f.actor,second.id,{version:1,answers:[]},'s2',true,'r');
    await f.pool.query("CREATE FUNCTION fail_grading_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='attempt.graded' THEN RAISE EXCEPTION 'injected failure'; END IF; RETURN NEW; END $$");
    await f.pool.query('CREATE TRIGGER inject_failure BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION fail_grading_audit()');
    await assert.rejects(() => f.exams.grade(f.actor,second.id,{version:2,scores},'g2','r'),/injected failure/);
    assert.equal((await f.repo.attempt(second.id))!.status,'submitted');
    assert.equal((await f.repo.rows('SELECT * FROM gradings')).length,1);
    assert.equal((await f.repo.rows('SELECT * FROM mistakes')).length,2);
    assert.equal((await f.repo.rows("SELECT * FROM outbox_events WHERE action='attempt.graded' AND object_id=$1",[second.id])).length,0);
  } finally { await f.close(); }
});

test('API07/IA02: own scope, answers hidden before submission, archived paper history survives', async () => {
  const f = await examFixture();
  try {
    const a = await f.start();
    assert.ok(a.snapshot.items.every((i:Row) => !('referenceAnswer' in i)));
    await assert.rejects(() => f.exams.detail(f.other,a.id,'r'),(e:Failure) => e.code==='RESOURCE_NOT_FOUND');
    await assert.rejects(() => f.exams.answers(f.other,a.id,{version:1,answers:[]},'other',true,'r'),(e:Failure) => e.code==='RESOURCE_NOT_FOUND');
    await assert.rejects(() => f.exams.grade(f.other,a.id,{version:1,scores:[]},'other','r'),(e:Failure) => e.code==='RESOURCE_NOT_FOUND');
    assert.equal((await f.exams.list(f.other,'attempts',{page:1,pageSize:20,sort:'createdAt',direction:'desc'},'r')).total,0);
    await f.pool.query("UPDATE papers SET status='archived' WHERE id=$1",[f.papers.fixed]);
    assert.equal((await f.exams.detail(f.actor,a.id,'r')).snapshot.title,'计算机基础练习');
  } finally { await f.close(); }
});

test('DT08-F: test target protection refuses inherited production settings', () => {
  for (const url of ['postgresql://exam_test@production.test/exam_test','postgresql://exam_test@127.0.0.1/production','postgresql://real_user@127.0.0.1/exam_test']) assert.throws(() => testTarget({EXAM_TEST_RUN_ID:randomUUID(),EXAM_TEST_DATABASE_URL:url}));
  assert.throws(() => testTarget({EXAM_TEST_DATABASE_URL:process.env.EXAM_TEST_DATABASE_URL}));
});

test('API04/05: start_at/end_at boundaries, repeat practice and grade directly after read-only expiry',async()=>{
  const f=await examFixture();
  try{
    for(const [time,allowed] of [['2025-12-31T23:59:59.999Z',false],['2026-01-01T00:00:00Z',true],['2026-01-01T00:00:00.001Z',true],['2026-12-31T23:59:59.999Z',true],['2027-01-01T00:00:00Z',false],['2027-01-01T00:00:00.001Z',false]] as const){
      f.time(time);
      if(allowed)assert.equal((await f.start('timed')).status,'running');
      else await assert.rejects(()=>f.start('timed'),(e:Failure)=>e.code==='BIZ_UNAVAILABLE');
    }
    f.time('2026-06-01T00:00:00Z');
    const first=await f.start(),second=await f.start();assert.notEqual(first.id,second.id);
    f.time('2026-06-01T00:10:00Z');
    const read=await f.exams.detail(f.actor,first.id,'r');assert.equal(read.version,1);assert.equal(read.status,'submitted');
    const scores=read.snapshot.items.map((item:Row)=>({itemId:item.id,score:item.maxScore}));
    const graded=await f.exams.grade(f.actor,first.id,{version:read.version,scores},'grade-expired','r');
    assert.equal(graded.status,'completed');assert.equal(graded.version,3);assert.equal(graded.total,'100.00');
    assert.equal((await f.repo.rows("SELECT * FROM audit_events WHERE action='attempt.expired' AND object_id=$1",[first.id])).length,1);
  }finally{await f.close();}
});

test('API06-F: score bounds, concurrent confirmations and latest wrong attempt aggregation',async()=>{
  const f=await examFixture();
  try{
    const a=await f.start();await f.exams.answers(f.actor,a.id,{version:1,answers:[]},'submit-a',true,'r');
    const base=a.snapshot.items.map((i:Row)=>({itemId:i.id,score:'20.00'}));
    for(const bad of ['-0.01','20.01','0.001','NaN'])await assert.rejects(()=>f.exams.grade(f.actor,a.id,{version:2,scores:base.map((s:Row,i:number)=>i? s:{...s,score:bad})},randomUUID(),'r'),(e:Failure)=>e.code==='PARAM_INVALID');
    assert.equal((await f.repo.rows('SELECT * FROM gradings')).length,0);
    const scores=base.map((s:Row,i:number)=>i?s:{...s,score:'0.00'});
    const attempts=await Promise.allSettled([f.exams.grade(f.actor,a.id,{version:2,scores},'concurrent-a','r'),f.exams.grade(f.actor,a.id,{version:2,scores},'concurrent-b','r')]);
    assert.equal(attempts.filter(r=>r.status==='fulfilled').length,1);assert.equal(attempts.filter(r=>r.status==='rejected').length,1);
    const b=await f.start();await f.exams.answers(f.actor,b.id,{version:1,answers:[]},'submit-b',true,'r');
    await f.exams.grade(f.actor,b.id,{version:2,scores},'grade-b','r');
    const mistakes=await f.repo.rows('SELECT * FROM mistakes');assert.equal(mistakes.length,1);assert.equal(mistakes[0].last_attempt_id,b.id);
    await assert.rejects(()=>f.exams.mistake(f.other,mistakes[0].id),(e:Failure)=>e.code==='RESOURCE_NOT_FOUND');
  }finally{await f.close();}
});
