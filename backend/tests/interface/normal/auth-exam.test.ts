import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture } from '../../database.ts';
import { seedExam } from '../../../src/infrastructure/seed.ts';
import { credentials } from '../../../src/infrastructure/credentials.ts';
import { Auth } from '../../../src/application/auth.ts';
import { Exams } from '../../../src/application/exams.ts';
import { buildServer } from '../../../src/interfaces/http.ts';
import { Failure } from '../../../src/domain/contracts.ts';
import type { Row, Repository } from '../../../src/domain/contracts.ts';

const password='Synthetic-password-2026';
const crypto=await credentials('synthetic-key-only-used-in-isolated-tests');
async function setup() {
  const db=await fixture();
  try {
    const content=await seedExam(db.pool);
    const auth=new Auth(db.repo,crypto,()=>new Date('2026-06-01T00:00:00Z'));
    const exams=new Exams(db.repo,()=>new Date('2026-06-01T00:00:00Z'));
    const app=buildServer({origins:['https://exam.test'],rateLimit:1000,security:auth,auth,exams});
    const base=await app.listen({host:'127.0.0.1',port:0});
    async function call(method:'GET'|'POST'|'PUT'|'DELETE',url:string,body?:Row,token?:string,extra:Record<string,string>={}) {
      const response=await fetch(base+url,{method,...(body?{body:JSON.stringify(body)}:{}),headers:{origin:'https://exam.test','idempotency-key':randomUUID(),...(body?{'content-type':'application/json'}:{}),...(token?{authorization:`Bearer ${token}`} : {}),...extra},signal:AbortSignal.timeout(10000)});
      const text=await response.text();
      return {statusCode:response.status,headers:Object.fromEntries(response.headers.entries()),body:text,json:()=>JSON.parse(text)};
    }
    return {...db,...content,auth,app,call,async close(){await app.close();await db.close();}};
  } catch(error){await db.close();throw error;}
}

test('API01/API05-07: independent HTTP client registers, starts, saves, submits, self-grades and queries history/mistakes',async()=>{
  const f=await setup();
  try {
    const registered=await f.call('POST','/api/v1/users',{username:'synthetic_student',password,gradeId:f.gradeId});
    assert.equal(registered.json().code,'OK');
    const login=await f.call('POST','/api/v1/sessions',{username:'synthetic_student',password});
    const session=login.json().data;
    assert.equal(login.json().code,'OK'); assert.ok(session.accessToken); assert.ok(!('refreshToken' in session));
    const token=session.accessToken;
    const cookie=String(login.headers['set-cookie']);
    assert.match(cookie,/__Host-exam_refresh=/);assert.match(cookie,/HttpOnly/);assert.match(cookie,/Secure/);assert.match(cookie,/SameSite=Strict/);assert.doesNotMatch(cookie,/Domain=/);
    const papers=(await f.call('GET','/api/v1/papers',undefined,token)).json().data;
    assert.equal(papers.total,2); assert.ok(!JSON.stringify(papers).includes('referenceAnswer'));
    const startKey=randomUUID();
    const attempt=(await f.call('POST',`/api/v1/papers/${f.papers.fixed}/attempts`,{},token,{'idempotency-key':startKey})).json().data;
    const caseRetry=(await f.call('POST',`/api/v1/papers/${f.papers.fixed.toUpperCase()}/attempts`,{},token,{'idempotency-key':startKey})).json().data;
    assert.equal(caseRetry.id,attempt.id);
    assert.equal(attempt.status,'running');assert.ok(attempt.snapshot.items.every((i:Row)=>!('referenceAnswer' in i)));
    const texts=['键盘','["键盘","鼠标"]','true','HTML','双重验证增加密码以外的验证。'];
    const answers=attempt.snapshot.items.map((item:Row,i:number)=>({itemId:item.id,text:texts[i]}));
    const saved=(await f.call('PUT',`/api/v1/attempts/${attempt.id}/answers`,{version:1,answers},token)).json().data;
    assert.equal(saved.version,2);assert.equal(saved.answers.length,5);
    const submitted=(await f.call('POST',`/api/v1/attempts/${attempt.id}/submissions`,{version:2,answers},token)).json().data;
    assert.equal(submitted.status,'submitted');assert.ok(submitted.snapshot.items.every((i:Row)=>'referenceAnswer' in i));
    const scoreBody={version:3,scores:submitted.snapshot.items.map((item:Row,i:number)=>({itemId:item.id,score:i===0?'10.25':'20.00'}))};
    const graded=(await f.call('POST',`/api/v1/attempts/${attempt.id}/gradings`,scoreBody,token)).json().data;
    assert.equal(graded.total,'90.25');assert.equal(graded.status,'completed');
    const records=(await f.call('GET','/api/v1/attempts?status=completed',undefined,token)).json().data;
    assert.equal(records.total,1);assert.equal(records.items[0].id,attempt.id);
    const mistakes=(await f.call('GET','/api/v1/mistakes',undefined,token)).json().data;
    assert.equal(mistakes.total,1);
    const detail=(await f.call('GET',`/api/v1/mistakes/${mistakes.items[0].id}`,undefined,token)).json().data;
    assert.equal(detail.snapshot.score,'10.25');assert.equal(detail.lastAttemptId,attempt.id);
    const denied=await f.call('PUT',`/api/v1/attempts/${attempt.id}/answers`,{version:graded.version,answers:[]},token);
    assert.equal(denied.json().code,'BIZ_STATE_CONFLICT');
    assert.equal((await f.repo.rows('SELECT * FROM gradings')).length,1);
    const serialized=JSON.stringify(await f.repo.rows('SELECT * FROM audit_events'));
    assert.ok(!serialized.includes(password));assert.ok(!serialized.includes(session.accessToken));
  }finally{await f.close();}
});

test('IA03/CT04: salted bcrypt, public role injection, wrong grade/password lengths and uniform login errors',async()=>{
  const f=await setup();
  try {
    for(const username of ['synthetic_a','synthetic_b'])assert.equal((await f.call('POST','/api/v1/users',{username,password,gradeId:f.gradeId})).json().code,'OK');
    const a=(await f.repo.userByName('synthetic_a'))!,b=(await f.repo.userByName('synthetic_b'))!;
    assert.notEqual(a.password_hash,b.password_hash);assert.match(a.password_hash,/^\$2[ab]\$12\$/);
    const before=(await f.repo.rows('SELECT * FROM users')).length;
    for(const body of [{username:'injected_role',password,gradeId:f.gradeId,role:'admin'},{username:'invalid_grade',password,gradeId:randomUUID()},{username:'short_password',password:'a'.repeat(11),gradeId:f.gradeId},{username:'over_bytes',password:'密'.repeat(25),gradeId:f.gradeId}]){
      const response=await f.call('POST','/api/v1/users',body);assert.equal(response.json().code,'PARAM_INVALID');
    }
    assert.equal((await f.repo.rows('SELECT * FROM users')).length,before);
    assert.equal((await f.call('POST','/api/v1/users',{username:'boundary_72bytes',password:'密'.repeat(24),gradeId:f.gradeId})).json().code,'OK');
    const missing=(await f.call('POST','/api/v1/sessions',{username:'unknown_user',password})).json();
    const wrong=(await f.call('POST','/api/v1/sessions',{username:'synthetic_a',password:'Incorrect-password-2026'})).json();
    assert.equal(missing.code,'AUTHN_INVALID');assert.equal(missing.message,wrong.message);
  }finally{await f.close();}
});

test('IA04/IA06: refresh rotates hash-only credentials, rejects CSRF/origin, replay revokes old access',async()=>{
  const f=await setup();
  try {
    await f.call('POST','/api/v1/users',{username:'refresh_student',password,gradeId:f.gradeId});
    const key=randomUUID();
    const login=await f.call('POST','/api/v1/sessions',{username:'refresh_student',password},undefined,{'idempotency-key':key});
    const repeated=await f.call('POST','/api/v1/sessions',{username:'refresh_student',password},undefined,{'idempotency-key':key});
    assert.deepEqual(repeated.json().data,login.json().data);
    assert.equal((await f.repo.rows('SELECT * FROM sessions')).length,1);
    const session=login.json().data,cookie=String(login.headers['set-cookie']).split(';')[0];
    const wrongOrigin=await f.call('POST','/api/v1/sessions/refreshes',{},undefined,{cookie,'x-csrf-token':session.csrfToken,origin:'https://attacker.test'});
    assert.equal(wrongOrigin.json().code,'AUTHZ_FORBIDDEN');
    const wrongCsrf=await f.call('POST','/api/v1/sessions/refreshes',{},undefined,{cookie,'x-csrf-token':'wrong'});
    assert.equal(wrongCsrf.json().code,'AUTHN_INVALID');
    const next=await f.call('POST','/api/v1/sessions/refreshes',{},undefined,{cookie,'x-csrf-token':session.csrfToken});
    assert.equal(next.json().code,'OK');assert.notEqual(next.headers['set-cookie'],login.headers['set-cookie']);
    const stored=JSON.stringify(await f.repo.rows('SELECT * FROM refresh_tokens'));
    assert.ok(!stored.includes(cookie.split('=')[1]));assert.equal((await f.repo.rows('SELECT * FROM refresh_tokens')).length,2);
    const replay=await f.call('POST','/api/v1/sessions/refreshes',{},undefined,{cookie,'x-csrf-token':session.csrfToken});
    assert.equal(replay.json().code,'AUTHN_SESSION_REVOKED');
    assert.equal((await f.call('GET','/api/v1/papers',undefined,next.json().data.accessToken)).json().code,'AUTHN_SESSION_REVOKED');
  }finally{await f.close();}
});

test('IA02/IA05: cross-user resources denied, password change and logout revoke immediately; auth failure closes access',async()=>{
  const f=await setup();
  try {
    for(const username of ['owner_student','other_student'])await f.call('POST','/api/v1/users',{username,password,gradeId:f.gradeId});
    const a=(await f.call('POST','/api/v1/sessions',{username:'owner_student',password})).json().data;
    const a2=(await f.call('POST','/api/v1/sessions',{username:'owner_student',password})).json().data;
    const b=(await f.call('POST','/api/v1/sessions',{username:'other_student',password})).json().data;
    const attempt=(await f.call('POST',`/api/v1/papers/${f.papers.fixed}/attempts`,{},a.accessToken)).json().data;
    for(const response of [await f.call('GET',`/api/v1/attempts/${attempt.id}`,undefined,b.accessToken),await f.call('POST',`/api/v1/attempts/${attempt.id}/submissions`,{version:1,answers:[]},b.accessToken)])assert.equal(response.json().code,'RESOURCE_NOT_FOUND');
    const changed=await f.call('POST',`/api/v1/users/${a.user.id}/password-changes`,{currentPassword:password,password:'Another-synthetic-password'},a.accessToken);
    assert.equal(changed.json().code,'OK');
    for(const token of [a.accessToken,a2.accessToken])assert.equal((await f.call('GET','/api/v1/papers',undefined,token)).json().code,'AUTHN_SESSION_REVOKED');
    const out=await f.call('DELETE',`/api/v1/sessions/${b.sessionId}`,undefined,b.accessToken,{'x-csrf-token':b.csrfToken});
    assert.equal(out.json().code,'OK');assert.equal((await f.call('GET','/api/v1/papers',undefined,b.accessToken)).json().code,'AUTHN_SESSION_REVOKED');
    const broken=new Auth({async session(){throw new Error('offline');}} as unknown as Repository,crypto,()=>new Date('2026-06-01'));
    await assert.rejects(()=>broken.authenticate(`Bearer ${b.accessToken}`),(error:Failure)=>error.code==='DEP_UNAVAILABLE');
    const fresh=(await f.call('POST','/api/v1/sessions',{username:'other_student',password})).json().data;
    await f.auth.revokeUser(fresh.user.id);
    assert.equal((await f.call('GET','/api/v1/papers',undefined,fresh.accessToken)).json().code,'AUTHN_SESSION_REVOKED');
  }finally{await f.close();}
});
