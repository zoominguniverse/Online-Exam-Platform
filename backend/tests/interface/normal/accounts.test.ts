import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture } from '../../database.ts';
import { seedExam } from '../../../src/infrastructure/seed.ts';
import { credentials } from '../../../src/infrastructure/credentials.ts';
import { SYSTEM } from '../../../src/infrastructure/postgres.ts';
import { Auth } from '../../../src/application/auth.ts';
import { Accounts } from '../../../src/application/accounts.ts';
import { Exams } from '../../../src/application/exams.ts';
import { buildServer } from '../../../src/interfaces/http.ts';
import type { Row } from '../../../src/domain/contracts.ts';

const password='Synthetic-management-password';
const crypto=await credentials('synthetic-account-management-test-key');
const initialHash=await crypto.hash(password);
async function setup() {
  const db=await fixture();
  try {
    const content=await seedExam(db.pool);
    const records:Record<string,Row>={};
    for(const [name,role] of [['admin_one','admin'],['admin_two','admin'],['initial_student','student']]){
      records[name]=await db.repo.transaction(r=>r.createUser({id:randomUUID(),username:name,passwordHash:initialHash,role,gradeId:role==='student'?content.gradeId:null,createdBy:SYSTEM}));
    }
    const now=()=>new Date('2026-06-01T00:00:00Z');
    const auth=new Auth(db.repo,crypto,now),accounts=new Accounts(db.repo,crypto,now);
    const app=buildServer({origins:['https://exam.test'],rateLimit:1000,auth,security:auth,accounts,exams:new Exams(db.repo,now)});
    const base=await app.listen({host:'127.0.0.1',port:0});
    async function call(method:'GET'|'POST'|'PUT'|'DELETE',url:string,body?:Row,token?:string,extra:Record<string,string>={}){
      const response=await fetch(base+url,{method,...(body?{body:JSON.stringify(body)}:{}),headers:{origin:'https://exam.test','idempotency-key':randomUUID(),...(body?{'content-type':'application/json'}:{}),...(token?{authorization:`Bearer ${token}`} : {}),...extra},signal:AbortSignal.timeout(10000)});
      const text=await response.text();return {statusCode:response.status,headers:Object.fromEntries(response.headers.entries()),body:text,json:()=>JSON.parse(text)};
    }
    async function login(username:string){const response=await call('POST','/api/v1/sessions',{username,password});assert.equal(response.json().code,'OK');return response.json().data;}
    return {...db,...content,records,auth,accounts,call,login,async close(){await app.close();await db.close();}};
  }catch(error){await db.close();throw error;}
}
function safe(value:unknown){assert.doesNotMatch(JSON.stringify(value),/password_hash|passwordHash|auth_version|token_hash|csrf_hash|created_by|\$2[ab]\$12\$/);}

test('API02-N/CT05: administrators create both account roles, edit, share authorized reads and replay safe results',async()=>{
  const f=await setup();
  try{
    const a=await f.login('admin_one'),b=await f.login('admin_two');
    const body={username:'managed_student',password,role:'student',gradeId:f.gradeId},key=randomUUID();
    const created=await f.call('POST','/api/v1/users',body,a.accessToken,{'idempotency-key':key});
    assert.equal(created.json().code,'OK');const student=created.json().data;
    assert.equal(student.role,'student');assert.equal(student.version,1);assert.equal(created.headers.etag,'"1"');safe(student);
    const retry=await f.call('POST','/api/v1/users',body,a.accessToken,{'idempotency-key':key});assert.deepEqual(retry.json().data,student);
    assert.equal((await f.repo.rows("SELECT * FROM audit_events WHERE action='user.created' AND object_id=$1",[student.id])).length,1);
    const admin=(await f.call('POST','/api/v1/users',{username:'managed_admin',password,role:'admin',gradeId:null},a.accessToken)).json();
    assert.equal(admin.code,'OK');assert.equal(admin.data.role,'admin');assert.equal(admin.data.gradeId,null);safe(admin.data);
    assert.equal((await f.call('GET',`/api/v1/users/${admin.data.id}`,undefined,b.accessToken)).json().data.username,'managed_admin');
    const changed=await f.call('PUT',`/api/v1/users/${student.id}`,{version:1,username:'Managed_Renamed',role:'student',gradeId:f.gradeId},a.accessToken);
    assert.equal(changed.json().code,'OK');assert.equal(changed.json().data.version,2);safe(changed.json().data);
    const duplicate=await f.call('POST','/api/v1/users',{...body,username:'managed_renamed'},a.accessToken);
    assert.equal(duplicate.statusCode,409);assert.equal(duplicate.json().code,'BIZ_USERNAME_EXISTS');
    const registered=await f.call('POST','/api/v1/users',{username:'public_student',password,gradeId:f.gradeId});
    assert.equal(registered.json().code,'OK');assert.equal(registered.json().data.role,'student');
    const duplicatePublic=await f.call('POST','/api/v1/users',{username:'PUBLIC_STUDENT',password,gradeId:f.gradeId});
    assert.equal(duplicatePublic.json().code,'BIZ_USERNAME_EXISTS');
  }finally{await f.close();}
});

test('API02-F/IA01/CT04: student and anonymous cannot manage accounts, system actor and forbidden fields stay hidden',async()=>{
  const f=await setup();
  try{
    const a=await f.login('admin_one'),s=await f.login('initial_student'),id=f.records.initial_student.id;
    const snapshot=await f.repo.userById(id),auditCount=(await f.repo.rows('SELECT * FROM audit_events')).length;
    for(const [method,url,body] of [
      ['GET','/api/v1/users',undefined],['GET',`/api/v1/users/${id}`,undefined],
      ['PUT',`/api/v1/users/${id}`,{version:1,username:'changed',role:'student',gradeId:f.gradeId}],
      ['POST',`/api/v1/users/${id}/suspensions`,{version:1}],['DELETE',`/api/v1/users/${id}`,undefined],
      ['POST','/api/v1/users',{username:'injected_admin',password,role:'admin',gradeId:null}],
    ] as const){
      const response=await f.call(method,url,body,s.accessToken,{'if-match':'"1"'});assert.equal(response.json().code,'AUTHZ_FORBIDDEN',`${method} ${url}`);
    }
    assert.equal((await f.call('GET','/api/v1/users')).json().code,'AUTHN_REQUIRED');
    assert.equal((await f.call('POST','/api/v1/users',{username:'anonymous_admin',password,role:'admin',gradeId:null})).json().code,'PARAM_INVALID');
    for(const body of [
      {version:1,username:'changed',role:'student',gradeId:f.gradeId,status:'deleted'},
      {version:1,username:'changed',role:'student',gradeId:f.gradeId,passwordHash:'injected'},
      {version:1,username:'changed',role:'system',gradeId:null},
      {version:1,username:'changed',role:'student',gradeId:randomUUID()},
      {version:1,username:'changed',role:'admin',gradeId:f.gradeId},
    ])assert.equal((await f.call('PUT',`/api/v1/users/${id}`,body,a.accessToken)).json().code,'PARAM_INVALID');
    assert.equal((await f.call('GET',`/api/v1/users/${SYSTEM}`,undefined,a.accessToken)).json().code,'RESOURCE_NOT_FOUND');
    assert.equal((await f.call('DELETE',`/api/v1/users/${id}`,undefined,a.accessToken)).json().code,'PARAM_INVALID');
    assert.equal((await f.call('DELETE',`/api/v1/users/${id}`,undefined,a.accessToken,{'if-match':'1'})).json().code,'PARAM_INVALID');
    assert.deepEqual(await f.repo.userById(id),snapshot);assert.equal((await f.repo.rows('SELECT * FROM audit_events')).length,auditCount);
  }finally{await f.close();}
});

test('API02-N/IA05: disable, role changes and deletion revoke access/refresh while preserving historical ownership',async()=>{
  const f=await setup();
  try{
    const a=await f.login('admin_one'),b=await f.login('admin_two');
    const loginResponse=await f.call('POST','/api/v1/sessions',{username:'initial_student',password});
    const s=loginResponse.json().data,s2=await f.login('initial_student');
    const cookie=String(loginResponse.headers['set-cookie']).split(';')[0];
    const attempt=(await f.call('POST',`/api/v1/papers/${f.papers.fixed}/attempts`,{},s.accessToken)).json().data;
    const id=f.records.initial_student.id;
    const disabled=await f.call('POST',`/api/v1/users/${id}/suspensions`,{version:1},a.accessToken);
    assert.equal(disabled.json().code,'OK');assert.equal(disabled.json().data.status,'disabled');assert.equal(disabled.json().data.version,2);
    for(const token of [s.accessToken,s2.accessToken])assert.equal((await f.call('GET','/api/v1/papers',undefined,token)).json().code,'AUTHN_SESSION_REVOKED');
    assert.equal((await f.call('POST','/api/v1/sessions/refreshes',{},undefined,{cookie,'x-csrf-token':s.csrfToken})).json().code,'AUTHN_SESSION_REVOKED');
    const demoted=await f.call('PUT',`/api/v1/users/${b.user.id}`,{version:1,username:'admin_two',role:'student',gradeId:f.gradeId},a.accessToken);
    assert.equal(demoted.json().code,'OK');assert.equal((await f.call('GET','/api/v1/users',undefined,b.accessToken)).json().code,'AUTHN_SESSION_REVOKED');
    const asStudent=await f.login('admin_two');assert.equal((await f.call('GET','/api/v1/users',undefined,asStudent.accessToken)).json().code,'AUTHZ_FORBIDDEN');
    const deleted=await f.call('DELETE',`/api/v1/users/${id}`,undefined,a.accessToken,{'if-match':'"2"'});
    assert.equal(deleted.json().code,'OK');assert.equal(deleted.json().data.status,'deleted');assert.equal(deleted.json().data.gradeId,null);
    assert.match(deleted.json().data.username,/^deleted_[0-9a-f]{24}$/);safe(deleted.json().data);
    const repeated=await f.call('DELETE',`/api/v1/users/${id}`,undefined,a.accessToken,{'if-match':'"2"'});assert.deepEqual(repeated.json().data,deleted.json().data);
    assert.equal((await f.repo.rows("SELECT * FROM audit_events WHERE action='user.deleted' AND object_id=$1",[id])).length,1);
    assert.equal((await f.repo.attempt(attempt.id))!.owner_id,id);
    const row=(await f.repo.userById(id))!;assert.notEqual(row.password_hash,initialHash);assert.equal(await crypto.verify(password,row.password_hash),false);
    assert.equal((await f.call('PUT',`/api/v1/users/${id}`,{version:3,username:'restore_deleted',role:'student',gradeId:f.gradeId},a.accessToken)).json().code,'BIZ_STATE_CONFLICT');
    assert.equal((await f.call('POST','/api/v1/sessions',{username:'initial_student',password})).json().code,'AUTHN_INVALID');
    const defaultList=(await f.call('GET','/api/v1/users',undefined,a.accessToken)).json().data;
    assert.ok(defaultList.items.every((user:Row)=>user.id!==id));
    const tombstones=(await f.call('GET','/api/v1/users?status=deleted',undefined,a.accessToken)).json().data;
    assert.deepEqual(tombstones.items.map((user:Row)=>user.id),[id]);safe(tombstones);
  }finally{await f.close();}
});

test('API02-F/CT05: concurrent editors cannot overwrite a version; duplicate keys conflict and stale deletes do nothing',async()=>{
  const f=await setup();
  try{
    const a=await f.login('admin_one'),b=await f.login('admin_two'),id=f.records.initial_student.id;
    const payload={version:1,username:'editor_one',role:'student',gradeId:f.gradeId},key=randomUUID();
    const results=await Promise.all([
      f.call('PUT',`/api/v1/users/${id}`,payload,a.accessToken,{'idempotency-key':key}),
      f.call('PUT',`/api/v1/users/${id}`,{...payload,username:'editor_two'},b.accessToken),
    ]);
    assert.equal(results.filter(r=>r.json().code==='OK').length,1);assert.equal(results.filter(r=>r.json().code==='BIZ_VERSION_CONFLICT').length,1);
    const current=(await f.repo.userById(id))!;assert.equal(current.version,2);
    const response=await f.call('DELETE',`/api/v1/users/${id}`,undefined,a.accessToken,{'if-match':'"1"'});assert.equal(response.json().code,'BIZ_VERSION_CONFLICT');
    assert.deepEqual(await f.repo.userById(id),current);
    const first=await f.call('PUT',`/api/v1/users/${id}`,{...payload,version:2,username:'repeat_editor'},a.accessToken,{'idempotency-key':'repeat-key'});
    assert.equal(first.json().code,'OK');
    const repeated=await f.call('PUT',`/api/v1/users/${id.toUpperCase()}`,{...payload,version:2,username:'repeat_editor'},a.accessToken,{'idempotency-key':'repeat-key'});assert.deepEqual(repeated.json().data,first.json().data);
    const conflict=await f.call('PUT',`/api/v1/users/${id}`,{...payload,version:2,username:'altered_content'},a.accessToken,{'idempotency-key':'repeat-key'});assert.equal(conflict.json().code,'BIZ_IDEMPOTENCY_CONFLICT');
    assert.equal((await f.repo.userById(id))!.username,'repeat_editor');
  }finally{await f.close();}
});

test('API02-F: database guard preserves one active admin under concurrent self-disable/delete/demotion',async()=>{
  for(const action of ['disable','delete','demote']){
    const f=await setup();
    try{
      const a=await f.login('admin_one'),b=await f.login('admin_two');
      const remove=(session:Row)=>action==='disable'?f.call('POST',`/api/v1/users/${session.user.id}/suspensions`,{version:1},session.accessToken):action==='delete'?f.call('DELETE',`/api/v1/users/${session.user.id}`,undefined,session.accessToken,{'if-match':'"1"'}):f.call('PUT',`/api/v1/users/${session.user.id}`,{version:1,username:session.user.username,role:'student',gradeId:f.gradeId},session.accessToken);
      const results=await Promise.all([remove(a),remove(b)]);
      assert.equal(results.filter(r=>r.json().code==='OK').length,1,action);assert.equal(results.filter(r=>r.json().code==='BIZ_LAST_ADMIN').length,1,action);
      assert.equal(await f.repo.activeAdministratorCount(),1);
      const survivor=results[0].json().code==='BIZ_LAST_ADMIN'?a:b;
      const before=await f.repo.userById(survivor.user.id);
      for(const response of [
        await f.call('POST',`/api/v1/users/${survivor.user.id}/suspensions`,{version:1},survivor.accessToken),
        await f.call('DELETE',`/api/v1/users/${survivor.user.id}`,undefined,survivor.accessToken,{'if-match':'"1"'}),
        await f.call('PUT',`/api/v1/users/${survivor.user.id}`,{version:1,username:survivor.user.username,role:'student',gradeId:f.gradeId},survivor.accessToken),
      ])assert.equal(response.json().code,'BIZ_LAST_ADMIN');
      assert.deepEqual(await f.repo.userById(survivor.user.id),before);
    }finally{await f.close();}
  }
});

test('CT06-N/F/API02: account filters, stable equal-key pages, safe output and no full-list fallback',async()=>{
  const f=await setup();
  try{
    const a=await f.login('admin_one');
    await f.pool.query("UPDATE users SET created_at='2026-01-01' WHERE role<>'system'");
    const ids:string[]=[];
    for(const page of [1,2]){
      const response=await f.call('GET',`/api/v1/users?page=${page}&pageSize=2&sort=createdAt&direction=asc`,undefined,a.accessToken);
      assert.equal(response.json().data.total,3);safe(response.json().data);ids.push(...response.json().data.items.map((u:Row)=>u.id));
    }
    assert.equal(new Set(ids).size,3);assert.deepEqual(ids,[...ids].sort());assert.ok(!ids.includes(SYSTEM));
    assert.equal((await f.call('GET','/api/v1/users?role=student&username=INITIAL_STUDENT',undefined,a.accessToken)).json().data.total,1);
    assert.equal((await f.call('GET',`/api/v1/users?gradeId=${f.gradeId}`,undefined,a.accessToken)).json().data.total,1);
    assert.equal((await f.call('GET','/api/v1/users?pageSize=101',undefined,a.accessToken)).json().data.pageSize,100);
    for(const query of ['role=system','status=unknown','sort=password_hash','unknown=x','pageSize=0',`gradeId=${randomUUID()}`,'username=%27%20OR%201%3D1']){
      const response=await f.call('GET',`/api/v1/users?${query}`,undefined,a.accessToken);assert.equal(response.json().code,'PARAM_INVALID',query);assert.equal(response.json().data,null);
    }
  }finally{await f.close();}
});

test('DT04-F/API02: audit fault rolls back status, anonymization and all session revocations',async()=>{
  const f=await setup();
  try{
    const a=await f.login('admin_one'),s=await f.login('initial_student'),id=f.records.initial_student.id;
    const before=await f.repo.userById(id),sessions=await f.repo.rows('SELECT * FROM sessions WHERE owner_id=$1 ORDER BY id',[id]);
    await f.pool.query("CREATE FUNCTION reject_account_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action IN ('user.suspended','user.deleted') THEN RAISE EXCEPTION 'private injected account failure'; END IF; RETURN NEW; END $$");
    await f.pool.query('CREATE TRIGGER fail_account_audit BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION reject_account_audit()');
    for(const response of [await f.call('POST',`/api/v1/users/${id}/suspensions`,{version:1},a.accessToken),await f.call('DELETE',`/api/v1/users/${id}`,undefined,a.accessToken,{'if-match':'"1"'})]){
      assert.equal(response.json().code,'INTERNAL_FAILURE');assert.doesNotMatch(response.body,/private injected|password|stack/);
    }
    assert.deepEqual(await f.repo.userById(id),before);assert.deepEqual(await f.repo.rows('SELECT * FROM sessions WHERE owner_id=$1 ORDER BY id',[id]),sessions);
    assert.equal((await f.call('GET','/api/v1/papers',undefined,s.accessToken)).json().code,'OK');
  }finally{await f.close();}
});
