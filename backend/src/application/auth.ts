import { randomUUID, createHash } from 'node:crypto';
import type { Credentials, Security } from '../domain/security.ts';
import { Failure, notFound, validatePassword as password } from '../domain/contracts.ts';
import type { Principal, Repository, Row } from '../domain/contracts.ts';
import { Exams } from './exams.ts';

const SYSTEM = '00000000-0000-4000-8000-000000000001';
const sha = (value: string) => createHash('sha256').update(value).digest('hex');
const publicUser = (row: Row) => ({ id:row.id,username:row.username,role:row.role,gradeId:row.grade_id });
export class Auth implements Security {
  private repo: Repository; private crypto: Credentials; private now: () => Date; private writes: Exams;
  constructor(repo: Repository, crypto: Credentials, now = () => new Date()) { this.repo=repo; this.crypto=crypto; this.now=now; this.writes=new Exams(repo,now); }
  async authenticate(authorization: string | undefined): Promise<Principal> {
    if (!authorization?.startsWith('Bearer ') || authorization.length>4096) throw new Failure('AUTHN_REQUIRED','请先登录。');
    const claims = this.crypto.parse(authorization.slice(7));
    if (!claims || !Number.isFinite(claims.exp) || claims.exp<=this.now().getTime() || typeof claims.sid!=='string' || !/^[0-9a-f-]{36}$/.test(claims.sid)) throw new Failure('AUTHN_INVALID','登录凭证已失效，请重新登录。');
    let session;
    try { session=await this.repo.session(claims.sid); }
    catch { throw new Failure('DEP_UNAVAILABLE','暂时无法验证会话，请稍后重试。'); }
    if (!session || session.revoked_at || session.user_status!=='active' || !['student','admin'].includes(session.role) || session.auth_version!==session.user_auth_version || session.owner_id!==claims.sub || session.auth_version!==claims.ver || new Date(session.expires_at)<=this.now()) throw new Failure('AUTHN_SESSION_REVOKED','会话已撤销，请重新登录。');
    return { id:session.owner_id,role:session.role,sessionId:session.id };
  }
  async authorize(actor: Principal, roles: readonly string[]) { if (!roles.includes(actor.role)) throw new Failure('AUTHZ_FORBIDDEN','当前账号无权执行此操作。'); }
  async revokeUser(id: string) { await this.repo.transaction(r=>r.revokeUser(id)); }
  async register(body: Row, key: string, requestId: string) {
    password(body.password);
    const passwordHash=await this.crypto.hash(body.password);
    const actor: Principal={id:SYSTEM,role:'admin',sessionId:SYSTEM};
    return this.writes.write(actor,'POST',`/api/v1/users/${body.username.toLowerCase()}`,key,{username:body.username,gradeId:body.gradeId,password:this.crypto.fingerprint(body.password)},async r=>{
      if (!await r.gradeExists(body.gradeId)) throw new Failure('PARAM_INVALID','年级不存在，请选择有效年级。');
      const id=randomUUID(); const row=await r.createUser({id,username:body.username,passwordHash,gradeId:body.gradeId,role:'student'});
      await r.audit(id,'user.registered',id,requestId); return publicUser(row);
    });
  }
  private issuance(data: Row) {
    return { accessToken:this.crypto.access({sub:data.ownerId,sid:data.sessionId,ver:data.authVersion,exp:data.issuedAt+900000}), expiresIn:900, sessionId:data.sessionId, csrfToken:this.crypto.csrf(data.sessionId), user:data.user, refreshToken:this.crypto.refresh(data.sessionId,data.refreshId) };
  }
  async login(body: Row, key: string, requestId: string) {
    password(body.password);
    const candidate=await this.repo.userByName(body.username);
    const matched=await this.crypto.verify(body.password,candidate?.password_hash||this.crypto.dummy());
    if (!matched || !candidate || candidate.status!=='active' || !['student','admin'].includes(candidate.role)) throw new Failure('AUTHN_INVALID','用户名或密码不正确，请检查后重试。');
    const scope=`${candidate.id}:POST:/api/v1/sessions`; const digest=this.crypto.fingerprint(`${body.username.toLowerCase()}\0${body.password}`);
    const data=await this.repo.transaction(async r=>{
      await r.lockKey(`${scope}:${key}`);
      const user=await r.userById(candidate.id);
      if (!user || user.status!=='active' || user.password_hash!==candidate.password_hash) throw new Failure('AUTHN_INVALID','用户名或密码不正确，请检查后重试。');
      const previous=await r.idempotency(scope,key);
      if (previous && new Date(previous.expires_at)>this.now()) {
        if (previous.digest!==digest) throw new Failure('BIZ_IDEMPOTENCY_CONFLICT','登录幂等键已用于其他内容。');
        const cached=previous.result;
        const session=await r.session(cached.sessionId);
        const refresh=await r.refreshByHash(sha(this.crypto.refresh(cached.sessionId,cached.refreshId)));
        if (!session || session.revoked_at || !refresh || refresh.consumed_at || cached.issuedAt+900000<=this.now().getTime()) throw new Failure('BIZ_STATE_CONFLICT','原登录结果已失效，请使用新的幂等键重新登录。');
        return cached;
      }
      const data={ownerId:user.id,authVersion:user.auth_version,sessionId:randomUUID(),refreshId:randomUUID(),issuedAt:this.now().getTime(),user:publicUser(user)};
      const expiresAt=new Date(data.issuedAt+7*86400000);
      await r.createSession({id:data.sessionId,ownerId:user.id,authVersion:user.auth_version,csrfHash:sha(this.crypto.csrf(data.sessionId)),expiresAt});
      await r.addRefresh({id:data.refreshId,sessionId:data.sessionId,ownerId:user.id,hash:sha(this.crypto.refresh(data.sessionId,data.refreshId)),expiresAt});
      await r.remember(scope,key,digest,data,this.now()); await r.audit(user.id,'session.created',data.sessionId,requestId); return data;
    });
    return this.issuance(data);
  }
  async refresh(token: string | undefined, csrf: string | undefined, requestId: string) {
    if (!token || token.length>512 || !csrf) throw new Failure('AUTHN_INVALID','刷新凭证无效，请重新登录。');
    const candidate=await this.repo.refreshByHash(sha(token));
    if (!candidate) throw new Failure('AUTHN_INVALID','刷新凭证无效，请重新登录。');
    const data=await this.repo.transaction(async r=>{
      const user=await r.userById(candidate.owner_id);
      const old=await r.refreshByHash(sha(token));
      if (!old || old.csrf_hash!==sha(csrf)) throw new Failure('AUTHN_INVALID','刷新凭证无效，请重新登录。');
      if (old.consumed_at) { await r.revokeSession(old.session_id); return null; }
      if (!user || user.status!=='active' || old.revoked_at || user.auth_version!==old.auth_version || new Date(old.expires_at)<=this.now() || new Date(old.session_expires_at)<=this.now()) throw new Failure('AUTHN_SESSION_REVOKED','会话已撤销或过期，请重新登录。');
      await r.consumeRefresh(old.id);
      const next={ownerId:user.id,authVersion:user.auth_version,sessionId:old.session_id,refreshId:randomUUID(),issuedAt:this.now().getTime(),user:publicUser(user)};
      await r.addRefresh({id:next.refreshId,sessionId:next.sessionId,ownerId:user.id,hash:sha(this.crypto.refresh(next.sessionId,next.refreshId)),expiresAt:old.session_expires_at});
      await r.audit(user.id,'session.refreshed',old.session_id,requestId); return next;
    });
    if (!data) throw new Failure('AUTHN_SESSION_REVOKED','检测到刷新凭证重放，请重新登录。');
    return this.issuance(data);
  }
  async verifyCsrf(actor: Principal,csrf: string | undefined) {
    const session=await this.repo.session(actor.sessionId);
    if (!session || !csrf || session.csrf_hash!==sha(csrf)) throw new Failure('AUTHZ_FORBIDDEN','请求校验失败，请刷新后重试。');
  }
  async logout(actor: Principal,id: string,requestId: string) {
    if (actor.sessionId!==id) throw notFound();
    await this.repo.transaction(async r=>{await r.userById(actor.id);await r.revokeSession(id);await r.audit(actor.id,'session.revoked',id,requestId);});
    return {revoked:true};
  }
  async changePassword(actor: Principal,id: string,body: Row,key: string,requestId: string) {
    if (actor.id!==id) throw notFound(); password(body.password); password(body.currentPassword);
    const current=await this.repo.userById(id);
    const matches=await this.crypto.verify(body.currentPassword,current?.password_hash||this.crypto.dummy());
    const hash=await this.crypto.hash(body.password);
    return this.writes.write(actor,'POST',`/api/v1/users/${id}/password-changes`,key,{password:this.crypto.fingerprint(body.password),currentPassword:this.crypto.fingerprint(body.currentPassword)},async r=>{
      const locked=await r.userById(id);
      if (!matches || !locked || locked.password_hash!==current?.password_hash) throw new Failure('AUTHN_INVALID','当前密码不正确，请重新输入。');
      await r.revokeUser(id,hash);await r.audit(id,'user.password_changed',id,requestId);return {changed:true};
    });
  }
}
