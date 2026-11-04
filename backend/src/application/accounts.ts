import { randomUUID, randomBytes } from 'node:crypto';
import { Failure, notFound, validatePassword } from '../domain/contracts.ts';
import type { Principal, Repository, Row, ListQuery } from '../domain/contracts.ts';
import type { Credentials } from '../domain/security.ts';
import { Exams } from './exams.ts';

function view(user: Row) {
  return {id:user.id,username:user.username,role:user.role,status:user.status,gradeId:user.grade_id,version:user.version,createdAt:user.created_at,updatedAt:user.updated_at};
}
export class Accounts {
  private repo: Repository;
  private crypto: Credentials;
  private writes: Exams;
  constructor(repo: Repository,crypto: Credentials,now=()=>new Date()) {this.repo=repo;this.crypto=crypto;this.writes=new Exams(repo,now);}
  private async administrator(r: Repository,actor: Principal) {
    if(actor.role!=='admin')throw new Failure('AUTHZ_FORBIDDEN','只有管理员可以管理账号。');
    const user=await r.userById(actor.id);
    if(!user || user.role!=='admin' || user.status!=='active')throw new Failure('AUTHZ_FORBIDDEN','管理权限已失效，请重新登录。');
  }
  private async target(r: Repository,id: string) {
    const user=await r.userById(id);
    if(!user || user.role==='system')throw notFound();
    return user;
  }
  private async grade(r: Repository,role: string,gradeId: string|null) {
    if(!['student','admin'].includes(role) || (role==='student' && (!gradeId || !await r.gradeExists(gradeId))) || (role==='admin' && gradeId!==null))throw new Failure('PARAM_INVALID','学生须使用有效年级，管理员的 gradeId 须为空。');
  }
  private version(user: Row,expected: number) {
    if(user.version!==expected)throw new Failure('BIZ_VERSION_CONFLICT','账号已更新，请刷新后重试。');
    if(user.status==='deleted')throw new Failure('BIZ_STATE_CONFLICT','账号已删除，不能恢复或修改。');
  }
  private async protect(r: Repository,current: Row,nextRole: string,nextStatus: string) {
    if(current.role==='admin' && current.status==='active' && (nextRole!=='admin' || nextStatus!=='active') && await r.activeAdministratorCount()<=1)throw new Failure('BIZ_LAST_ADMIN','至少保留一个启用管理员，请先新增另一位管理员。');
  }
  async list(actor: Principal,q: ListQuery) {
    await this.administrator(this.repo,actor);
    if(q.gradeId && !await this.repo.gradeExists(q.gradeId))throw new Failure('PARAM_INVALID','筛选年级不存在。');
    const result=await this.repo.listUsers(q);return {...result,items:result.items.map(view)};
  }
  async detail(actor: Principal,id: string) {
    await this.administrator(this.repo,actor);return view(await this.target(this.repo,id));
  }
  async create(actor: Principal,body: Row,key: string,requestId: string) {
    await this.administrator(this.repo,actor);
    validatePassword(body.password);
    const hash=await this.crypto.hash(body.password);
    const digestBody={...body,password:this.crypto.fingerprint(body.password)};
    return this.writes.write(actor,'POST','/api/v1/users',key,digestBody,async r=>{
      await r.lockAdministrators();await this.administrator(r,actor);await this.grade(r,body.role,body.gradeId);
      const user=await r.createUser({id:randomUUID(),username:body.username,passwordHash:hash,role:body.role,gradeId:body.gradeId,createdBy:actor.id});
      await r.audit(actor.id,'user.created',user.id,requestId);return view(user);
    });
  }
  async update(actor: Principal,id: string,body: Row,key: string,requestId: string) {
    await this.administrator(this.repo,actor);
    return this.writes.write(actor,'PUT',`/api/v1/users/${id}`,key,body,async r=>{
      await r.lockAdministrators();await this.administrator(r,actor);
      const current=await this.target(r,id);this.version(current,body.version);await this.grade(r,body.role,body.gradeId);
      await this.protect(r,current,body.role,current.status);
      const user=await r.updateUser(id,body.version,{username:body.username,role:body.role,gradeId:body.gradeId,status:current.status});
      if(current.role!==body.role)await r.revokeUser(id);
      await r.audit(actor.id,'user.updated',id,requestId);return view(user);
    });
  }
  async suspend(actor: Principal,id: string,body: Row,key: string,requestId: string) {
    await this.administrator(this.repo,actor);
    return this.writes.write(actor,'POST',`/api/v1/users/${id}/suspensions`,key,body,async r=>{
      await r.lockAdministrators();await this.administrator(r,actor);
      const current=await this.target(r,id);this.version(current,body.version);
      if(current.status!=='active')throw new Failure('BIZ_STATE_CONFLICT','账号已禁用，请刷新查看。');
      await this.protect(r,current,current.role,'disabled');
      const user=await r.updateUser(id,body.version,{username:current.username,role:current.role,gradeId:current.grade_id,status:'disabled'});
      await r.revokeUser(id);await r.audit(actor.id,'user.suspended',id,requestId);return view(user);
    });
  }
  async remove(actor: Principal,id: string,version: number,requestId: string) {
    // Hashing is outside the transaction; deleted accounts retain no usable password.
    await this.administrator(this.repo,actor);
    const unusableHash=await this.crypto.hash(randomBytes(32).toString('hex'));
    return this.repo.transaction(async r=>{
      await r.lockAdministrators();await this.administrator(r,actor);
      const current=await this.target(r,id);
      if(current.status==='deleted')return view(current);
      this.version(current,version);await this.protect(r,current,current.role,'deleted');
      const alias=`deleted_${randomBytes(12).toString('hex')}`;
      const user=await r.updateUser(id,version,{username:alias,role:current.role,gradeId:null,status:'deleted'});
      await r.revokeUser(id,unusableHash);await r.audit(actor.id,'user.deleted',id,requestId);return view(user);
    });
  }
}
