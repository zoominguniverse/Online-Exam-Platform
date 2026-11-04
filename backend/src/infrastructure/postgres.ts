import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import type { PoolClient } from 'pg';
import { Failure, cents } from '../domain/contracts.ts';
import type { Repository, Row, ListQuery } from '../domain/contracts.ts';

export const SYSTEM = '00000000-0000-4000-8000-000000000001';
export class Postgres implements Repository {
  private db: Pool | PoolClient;
  private inTransaction: boolean;
  constructor(db: Pool | PoolClient, inTransaction = false) { this.db = db; this.inTransaction = inTransaction; }
  async rows(sql: string, args: any[] = []): Promise<Row[]> { return (await this.db.query(sql, args)).rows; }
  async one(sql: string, args: any[] = []) { return (await this.rows(sql, args))[0] || null; }
  async transaction<T>(work: (repo: Repository) => Promise<T>): Promise<T> {
    if (this.inTransaction) return work(this);
    const client = await (this.db as Pool).connect();
    try { await client.query('BEGIN'); const result = await work(new Postgres(client, true)); await client.query('COMMIT'); return result; }
    catch (error) { await client.query('ROLLBACK'); throw error; }
    finally { client.release(); }
  }
  private lock() { return this.inTransaction ? ' FOR UPDATE' : ''; }
  userByName(name: string) { return this.one('SELECT * FROM users WHERE username_normalized=$1' + this.lock(), [name.toLowerCase()]); }
  userById(id: string) { return this.one('SELECT * FROM users WHERE id=$1' + this.lock(), [id]); }
  async gradeExists(id: string) { return !!await this.one('SELECT id FROM grades WHERE id=$1', [id]); }
  async createUser(d: Row) {
    try { return (await this.one('INSERT INTO users(id,username,username_normalized,password_hash,role,grade_id,created_by) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING *', [d.id,d.username,d.username.toLowerCase(),d.passwordHash,d.role,d.gradeId,d.createdBy || d.id]))!; }
    catch (error) { if ((error as Row).code === '23505') throw new Failure('BIZ_USERNAME_EXISTS', '用户名已存在，请更换后重试。'); throw error; }
  }
  async lockAdministrators() {
    try{
      const guard=await this.one("SELECT id FROM account_guards WHERE id='00000000-0000-4000-8000-000000000002' FOR UPDATE");
      if(!guard)throw new Failure('DEP_UNAVAILABLE','账号管理迁移尚未就绪，请联系管理员。');
    }catch(error){
      if(['42P01','42501'].includes((error as Row).code))throw new Failure('DEP_UNAVAILABLE','账号管理结构或权限尚未就绪，请联系管理员。');
      throw error;
    }
  }
  async activeAdministratorCount() {return Number((await this.one("SELECT count(*) AS count FROM users WHERE role='admin' AND status='active'"))!.count);}
  async updateUser(id: string,version: number,d: Row) {
    try{
      const user=await this.one('UPDATE users SET username=$3,username_normalized=$7,role=$4,grade_id=$5,status=$6,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=$1 AND version=$2 RETURNING *',[id,version,d.username,d.role,d.gradeId,d.status,d.username.toLowerCase()]);
      if(!user)throw new Failure('BIZ_VERSION_CONFLICT','账号已更新，请刷新后重试。');return user;
    }catch(error){if((error as Row).code==='23505')throw new Failure('BIZ_USERNAME_EXISTS','用户名已存在，请更换后重试。');if((error as Row).code==='23503')throw new Failure('PARAM_INVALID','关联年级不存在，请重新选择。');throw error;}
  }
  async listUsers(q: ListQuery): Promise<Row> {
    const sort={createdAt:'created_at',username:'username_normalized'}[q.sort];
    if(!sort || !['asc','desc'].includes(q.direction) || !Number.isSafeInteger(q.page) || q.page<1 || !Number.isSafeInteger(q.pageSize) || q.pageSize<1 || q.pageSize>100)throw new Failure('PARAM_INVALID','账号查询分页或排序不合法。');
    const args:any[]=[],clauses=["role IN ('student','admin')"];
    if(!q.status)clauses.push("status<>'deleted'");
    for(const [key,column,allowed] of [['role','role',['student','admin']],['status','status',['active','disabled','deleted']],['gradeId','grade_id',null],['username','username_normalized',null]] as const){
      const value=q[key];if(!value)continue;
      if(allowed && !(allowed as readonly string[]).includes(value))throw new Failure('PARAM_INVALID','账号查询筛选值不合法。');
      args.push(key==='username'?value.toLowerCase():value);clauses.push(`${column}=$${args.length}`);
    }
    const result=await this.one(`WITH matched AS (SELECT id,username,username_normalized,role,status,grade_id,version,created_at,updated_at FROM users WHERE ${clauses.join(' AND ')}),page AS (SELECT * FROM matched ORDER BY ${sort} ${q.direction},id ${q.direction} LIMIT $${args.length+1} OFFSET $${args.length+2}) SELECT COALESCE((SELECT jsonb_agg(to_jsonb(page) ORDER BY ${sort} ${q.direction},id ${q.direction}) FROM page),'[]'::jsonb) AS items,(SELECT count(*)::integer FROM matched) AS total`,[...args,q.pageSize,(q.page-1)*q.pageSize]);
    return {...result,page:q.page,pageSize:q.pageSize};
  }
  async createSession(d: Row) { await this.rows('INSERT INTO sessions(id,owner_id,auth_version,csrf_hash,expires_at,created_by) VALUES($1,$2,$3,$4,$5,$2)', [d.id,d.ownerId,d.authVersion,d.csrfHash,d.expiresAt]); }
  session(id: string) { return this.one('SELECT s.*,u.auth_version AS user_auth_version,u.status AS user_status,u.role FROM sessions s JOIN users u ON u.id=s.owner_id WHERE s.id=$1', [id]); }
  async revokeSession(id: string) { await this.rows('UPDATE sessions SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=$1', [id]); }
  async revokeUser(id: string, passwordHash?: string) {
    await this.rows('UPDATE users SET auth_version=auth_version+1,password_hash=COALESCE($2,password_hash),updated_at=CURRENT_TIMESTAMP WHERE id=$1', [id,passwordHash || null]);
    await this.rows('UPDATE sessions SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE owner_id=$1', [id]);
  }
  refreshByHash(hash: string) { return this.one('SELECT rt.*,s.owner_id,s.auth_version,s.csrf_hash,s.revoked_at,s.expires_at AS session_expires_at FROM refresh_tokens rt JOIN sessions s ON s.id=rt.session_id WHERE rt.token_hash=$1' + (this.inTransaction ? ' FOR UPDATE OF rt,s' : ''), [hash]); }
  async consumeRefresh(id: string) { await this.rows('UPDATE refresh_tokens SET consumed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=$1', [id]); }
  async addRefresh(d: Row) { await this.rows('INSERT INTO refresh_tokens(id,session_id,token_hash,expires_at,created_by) VALUES($1,$2,$3,$4,$5)', [d.id,d.sessionId,d.hash,d.expiresAt,d.ownerId]); }
  paper(id: string) { return this.one('SELECT p.*,v.id AS paper_version_id,v.snapshot FROM papers p JOIN paper_versions v ON v.paper_id=p.id AND v.version=p.current_version WHERE p.id=$1', [id]); }
  task(id: string) { return this.one('SELECT t.*,v.snapshot FROM tasks t JOIN paper_versions v ON v.id=t.paper_version_id WHERE t.id=$1', [id]); }
  async eligible(taskId: string, ownerId: string) { return !!await this.one('SELECT id FROM task_recipients WHERE task_id=$1 AND owner_id=$2', [taskId,ownerId]); }
  attempt(id: string) { return this.one('SELECT * FROM attempts WHERE id=$1' + this.lock(), [id]); }
  taskAttempt(taskId: string, ownerId: string) { return this.one('SELECT * FROM attempts WHERE task_id=$1 AND owner_id=$2' + this.lock(), [taskId,ownerId]); }
  async createAttempt(d: Row) { return (await this.one('INSERT INTO attempts(id,owner_id,paper_version_id,task_id,snapshot,started_at,deadline,created_by) VALUES($1,$2,$3,$4,$5,$6,$7,$2) RETURNING *', [d.id,d.ownerId,d.paperVersionId,d.taskId,d.snapshot,d.now,d.deadline]))!; }
  async updateAttempt(id: string, version: number, d: Row) {
    const row = await this.one('UPDATE attempts SET status=$3,submitted_at=$4,total=$5,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=$1 AND version=$2 RETURNING *', [id,version,d.status,d.submittedAt,d.total ?? null]);
    if (!row) throw new Failure('BIZ_VERSION_CONFLICT', '答卷已更新，请刷新后重试。');
    return row;
  }
  async saveAnswers(id: string, answers: Row[]) {
    for (const a of answers) await this.rows('INSERT INTO attempt_answers(id,attempt_id,paper_version_id,item_id,answer,created_by) SELECT $1,id,paper_version_id,$3,$4,owner_id FROM attempts WHERE id=$2 ON CONFLICT(attempt_id,item_id) DO UPDATE SET answer=EXCLUDED.answer,updated_at=CURRENT_TIMESTAMP', [randomUUID(),id,a.itemId,a.text]);
  }
  answers(id: string) { return this.rows('SELECT item_id AS "itemId",answer AS text FROM attempt_answers WHERE attempt_id=$1 ORDER BY item_id', [id]); }
  mistake(id: string) { return this.one('SELECT * FROM mistakes WHERE id=$1',[id]); }
  async createGrading(attempt: Row, scores: Row[], total: string, actor: string) {
    await this.rows('INSERT INTO gradings(id,attempt_id,scores,total,created_by) VALUES($1,$2,$3,$4,$5)', [randomUUID(),attempt.id,JSON.stringify(scores),total,actor]);
    const answers = await this.answers(attempt.id);
    for (const score of scores) {
      const item = attempt.snapshot.items.find((i: Row) => i.id === score.itemId);
      if (cents(score.score) < cents(item.maxScore)) await this.rows('INSERT INTO mistakes(id,owner_id,question_id,last_attempt_id,snapshot,created_by) VALUES($1,$2,$3,$4,$5,$2) ON CONFLICT(owner_id,question_id) DO UPDATE SET last_attempt_id=EXCLUDED.last_attempt_id,snapshot=EXCLUDED.snapshot,updated_at=CURRENT_TIMESTAMP', [randomUUID(),actor,item.questionId,attempt.id,{ ...item, answer: answers.find(a => a.itemId === item.id)?.text || '', score: score.score }]);
    }
  }
  async list(kind: 'papers' | 'attempts' | 'mistakes', owner: string, q: ListQuery, now = new Date()): Promise<Row> {
    // Only constants from this table become SQL identifiers; no request value is interpolated.
    const columns: Record<string, Record<string, string>> = { papers: { createdAt: 'created_at', title: 'title' }, attempts: { createdAt: 'created_at' }, mistakes: { createdAt: 'created_at' } };
    const field = columns[kind]?.[q.sort];
    if (!field || !['asc','desc'].includes(q.direction) || !Number.isSafeInteger(q.page) || q.page < 1 || !Number.isSafeInteger(q.pageSize) || q.pageSize < 1 || q.pageSize > 100) throw new Failure('PARAM_INVALID', '分页或排序参数不合法。');
    const args: any[] = [];
    let where = kind === 'papers' ? "status='published' AND type<>'task'" : 'owner_id=$1';
    if (kind !== 'papers') args.push(owner);
    if (q.type) {
      if (kind !== 'papers' || !['fixed','timed'].includes(q.type)) throw new Failure('PARAM_INVALID', '试卷类型不合法。');
      args.push(q.type); where += ` AND type=$${args.length}`;
    }
    if (q.status) {
      if (kind !== 'attempts' || !['running','submitted','completed'].includes(q.status)) throw new Failure('PARAM_INVALID', '答卷状态不合法。');
      args.push(now,q.status); where += ` AND (CASE WHEN status='running' AND deadline<=$${args.length - 1} THEN 'submitted' ELSE status END)=$${args.length}`;
    }
    // A single statement gives items and total the same database snapshot.
    const result = await this.one(`WITH matched AS (SELECT * FROM ${kind} WHERE ${where}), page AS (SELECT * FROM matched ORDER BY ${field} ${q.direction},id ${q.direction} LIMIT $${args.length + 1} OFFSET $${args.length + 2}) SELECT COALESCE((SELECT jsonb_agg(to_jsonb(page) ORDER BY ${field} ${q.direction},id ${q.direction}) FROM page),'[]'::jsonb) AS items,(SELECT count(*)::integer FROM matched) AS total`, [...args,q.pageSize,(q.page - 1) * q.pageSize]);
    return { ...result, page: q.page, pageSize: q.pageSize };
  }
  async lockKey(scope: string) { await this.rows('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [scope]); }
  idempotency(scope: string, key: string) { return this.one('SELECT * FROM idempotency_records WHERE scope=$1 AND key=$2', [scope,key]); }
  async remember(scope: string, key: string, digest: string, result: Row, now: Date) {
    await this.rows('INSERT INTO idempotency_records(id,scope,key,digest,result,expires_at,created_by) VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT(scope,key) DO UPDATE SET digest=EXCLUDED.digest,result=EXCLUDED.result,expires_at=EXCLUDED.expires_at,updated_at=CURRENT_TIMESTAMP', [randomUUID(),scope,key,digest,result,new Date(now.getTime() + 86400000),scope.split(':')[0]]);
  }
  async audit(actor: string, action: string, objectId: string, requestId: string) { await this.rows('INSERT INTO audit_events(id,action,object_id,request_id,created_by) VALUES($1,$2,$3,$4,$5)', [randomUUID(),action,objectId,requestId,actor]); }
  async enqueue(actor: string, action: string, objectId: string) { await this.rows('INSERT INTO outbox_events(id,action,object_id,payload,created_by) VALUES($1,$2,$3,$4,$5) ON CONFLICT(action,object_id) DO NOTHING', [randomUUID(),action,objectId,{ objectId },actor]); }
  async due(now: Date, limit = 100) { return this.rows("SELECT * FROM attempts WHERE status='running' AND deadline<=$1 ORDER BY deadline,id LIMIT $2", [now,limit]); }
}
