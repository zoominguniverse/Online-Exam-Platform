import Fastify from 'fastify';
import cors from '@fastify/cors';
import cookie from '@fastify/cookie';
import { randomUUID } from 'node:crypto';
import { errors, Failure } from '../domain/contracts.ts';
import type { Principal, Row } from '../domain/contracts.ts';
import { DenySecurity } from '../domain/security.ts';
import type { Security } from '../domain/security.ts';
import type { Exams } from '../application/exams.ts';
import type { Auth } from '../application/auth.ts';
import type { Accounts } from '../application/accounts.ts';
import { routes, schema, query } from './routes.ts';

type Options = { origins: string[]; security?: Security; auth?: Auth; accounts?:Accounts; exams?: Exams; ready?: () => Promise<boolean>; rateLimit: number };
export function buildServer(options: Options) {
  const app = Fastify({ logger: false, bodyLimit: 128 * 1024, ajv: { customOptions: { removeAdditional: false, coerceTypes: false, allErrors: false } }, genReqId: () => randomUUID() });
  const security = options.security || new DenySecurity();
  const limits = new Map<string, { count: number; until: number }>();
  app.register(cors, { origin: options.origins, credentials: true, strictPreflight: true });
  app.register(cookie);
  app.addHook('onRequest', async (req, reply) => {
    reply.header('x-request-id', req.id).header('x-content-type-options', 'nosniff').header('cache-control', 'no-store');
    if (req.headers.origin && !options.origins.includes(req.headers.origin)) throw new Failure('AUTHZ_FORBIDDEN', '请求来源不受允许，请从正式入口访问。');
    const now = Date.now();
    if (limits.size > 10000) for (const [key, value] of limits) if (value.until <= now) limits.delete(key);
    const key = req.ip;
    const entry = limits.get(key);
    if (!entry || entry.until <= now) limits.set(key, { count: 1, until: now + 60000 });
    else if (++entry.count > options.rateLimit) { reply.header('retry-after', Math.ceil((entry.until - now) / 1000)); throw new Failure('RATE_LIMITED', '请求过于频繁，请稍后重试。'); }
    // Every route must have an explicit access policy, including routes added later.
    if (req.method === 'OPTIONS') return;
    const policy = req.routeOptions.config as { access?: 'public' | readonly string[] };
    if (!policy.access) throw new Failure('AUTHZ_FORBIDDEN', '此接口未获授权，请联系管理员。');
    if (policy.access !== 'public') {
      const principal = await security.authenticate(req.headers.authorization);
      await security.authorize(principal, policy.access);
      (req as typeof req & { principal: Principal }).principal = principal;
    }
  });
  const envelope = (requestId: string, data: unknown) => ({ code: 'OK', message: '操作成功', data, requestId });
  app.setErrorHandler((error, req, reply) => {
    let failure: Failure;
    if (error instanceof Failure && errors[error.code]) failure = error;
    else if ((error as Row).validation || ['FST_ERR_CTP_INVALID_JSON_BODY', 'FST_ERR_CTP_BODY_TOO_LARGE', 'FST_ERR_CTP_INVALID_MEDIA_TYPE', 'FST_ERR_CTP_EMPTY_JSON_BODY'].includes((error as Row).code)) failure = new Failure('PARAM_INVALID', '请求字段、类型或大小不合法，请按接口文档修改。');
    else if (['ECONNREFUSED', 'ECONNRESET', '57P01', '53300'].includes((error as Row).code)) failure = new Failure('DEP_UNAVAILABLE', '数据服务暂不可用，请稍后重试。');
    else failure = new Failure('INTERNAL_FAILURE', '请求未能完成，请凭请求标识联系管理员。');
    reply.code(errors[failure.code]).send({ code: failure.code, message: failure.message, data: failure.data, requestId: req.id });
  });
  app.setNotFoundHandler({ preHandler: async () => { throw new Failure('RESOURCE_NOT_FOUND', '接口不存在，请检查路径。'); } }, async () => {});
  app.get('/health/live', { config: { access: 'public' } }, async req => envelope(req.id, { live: true }));
  app.get('/health/ready', { config: { access: 'public' } }, async req => {
    if (!options.ready || !(await options.ready())) throw new Failure('DEP_UNAVAILABLE', '数据服务尚未就绪，请稍后重试。');
    return envelope(req.id, { ready: true });
  });
  for (const route of routes) app.route({
    method: route.method, url: route.url, schema: schema(route), config: { access: route.public?'public':route.roles },
    handler: async (req,reply) => {
      if(['listUsers','getUser','updateUser','suspendUser','deleteUser'].includes(route.operation)) {
        if(!options.accounts)throw new Failure('DEP_UNAVAILABLE','账号管理服务尚未就绪，请稍后重试。');
        const actor=(req as typeof req & {principal:Principal}).principal,id=(req.params as Row)?.id?.toLowerCase(),body=req.body as Row,key=req.headers['idempotency-key'] as string;
        const accounts=options.accounts;
        let data: Row;
        if(route.operation==='listUsers')data=await accounts.list(actor,query(req.query as Record<string,string>));
        else if(route.operation==='getUser')data=await accounts.detail(actor,id);
        else if(route.operation==='updateUser')data=await accounts.update(actor,id,body,key,req.id);
        else if(route.operation==='suspendUser')data=await accounts.suspend(actor,id,body,key,req.id);
        else data=await accounts.remove(actor,id,Number((req.headers['if-match'] as string).slice(1,-1)),req.id);
        if(Number.isInteger(data.version))reply.header('etag',`"${data.version}"`);
        return envelope(req.id,data);
      }
      if (['registerStudent','createSession','refreshSession','deleteSession','changePassword'].includes(route.operation)) {
        if (!options.auth) throw new Failure('DEP_UNAVAILABLE','身份服务尚未就绪，请稍后重试。');
        const auth=options.auth, body=req.body as Row, id=(req.params as Row)?.id?.toLowerCase(), actor=(req as typeof req & {principal:Principal}).principal;
        const key=req.headers['idempotency-key'] as string;
        const csrf=req.headers['x-csrf-token'] as string|undefined;
        if (['createSession','refreshSession','deleteSession'].includes(route.operation) && (!req.headers.origin || !options.origins.includes(req.headers.origin))) throw new Failure('AUTHZ_FORBIDDEN','会话请求须从允许的来源发起。');
        let data;
        if (route.operation==='registerStudent') {
          if('role' in body){
            if(!req.headers.authorization)throw new Failure('PARAM_INVALID','公开注册不允许指定角色。');
            const manager=await security.authenticate(req.headers.authorization);await security.authorize(manager,['admin']);
            if(!options.accounts)throw new Failure('DEP_UNAVAILABLE','账号管理服务尚未就绪，请稍后重试。');
            data=await options.accounts.create(manager,body,key,req.id);reply.header('etag',`"${data.version}"`);
          }else data=await auth.register(body,key,req.id);
        }
        else if (route.operation==='createSession' || route.operation==='refreshSession') {
          const issuance=route.operation==='createSession'?await auth.login(body,key,req.id):await auth.refresh(req.cookies['__Host-exam_refresh'],csrf,req.id);
          const {refreshToken,...publicData}=issuance;
          reply.setCookie('__Host-exam_refresh',refreshToken,{httpOnly:true,secure:true,sameSite:'strict',path:'/',maxAge:7*86400});
          data=publicData;
        } else if (route.operation==='deleteSession') {
          await auth.verifyCsrf(actor,csrf); data=await auth.logout(actor,id,req.id);
          reply.clearCookie('__Host-exam_refresh',{httpOnly:true,secure:true,sameSite:'strict',path:'/'});
        } else data=await auth.changePassword(actor,id,body,key,req.id);
        return envelope(req.id,data);
      }
      if (!options.exams) throw new Failure('DEP_UNAVAILABLE', '考试业务服务尚未就绪，请稍后重试。');
      const actor = (req as typeof req & { principal: Principal }).principal;
      const id = (req.params as Row)?.id?.toLowerCase();
      const body = req.body as Row;
      const key = req.headers['idempotency-key'] as string;
      const exams = options.exams;
      let data;
      switch (route.operation) {
        case 'listPapers': case 'listAttempts': case 'listMistakes':
          data = await exams.list(actor, route.operation === 'listPapers' ? 'papers' : route.operation === 'listAttempts' ? 'attempts' : 'mistakes', query(req.query as Record<string, string>), req.id); break;
        case 'getPaper': data = await exams.paper(actor, id); break;
        case 'getAttempt': data = await exams.detail(actor, id, req.id); break;
        case 'getMistake': data=await exams.mistake(actor,id); break;
        case 'startPaper': case 'startTask': data = await exams.start(actor, id, route.operation === 'startTask', key, `/api/v1/${route.operation === 'startTask' ? 'tasks' : 'papers'}/${id}/attempts`, req.id); break;
        case 'saveAnswers': case 'submitAttempt': data = await exams.answers(actor, id, body, key, route.operation === 'submitAttempt', req.id); break;
        case 'gradeAttempt': data = await exams.grade(actor, id, body, key, req.id); break;
        default: throw new Failure('AUTHZ_FORBIDDEN', '未登记业务操作。');
      }
      return envelope(req.id, data);
    },
  });
  return app;
}
