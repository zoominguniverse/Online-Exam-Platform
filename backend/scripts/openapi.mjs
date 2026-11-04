import { writeFile, readFile, mkdir } from 'node:fs/promises';
import { routes, schema } from '../src/interfaces/routes.ts';
import { errors } from '../src/domain/contracts.ts';

export function document() {
  const paths = {};
  const error = { type: 'object', required: ['code', 'message', 'data', 'requestId'], additionalProperties: false, properties: { code: { type: 'string', enum: Object.keys(errors) }, message: { type: 'string' }, data: { type: ['object', 'null'] }, requestId: { type: 'string' } } };
  const success = { ...error, properties: { ...error.properties, code: { const: 'OK' }, data: {} } };
  for (const route of routes) {
    const path = route.url.replace(':id', '{id}');
    const input = schema(route);
    const parameters = [];
    if (input.params) parameters.push({ name: 'id', in: 'path', required: true, schema: input.params.properties.id });
    if (route.query) for (const [name, value] of Object.entries(route.query.properties)) parameters.push({ name, in: 'query', required: false, schema: value });
    if (route.keyed) parameters.push({ name: 'Idempotency-Key', in: 'header', required: true, schema: input.headers.properties['idempotency-key'] });
    if (route.csrf) parameters.push({name:'X-CSRF-Token',in:'header',required:true,schema:input.headers.properties['x-csrf-token']});
    if(route.conditionalVersion)parameters.push({name:'If-Match',in:'header',required:true,schema:input.headers.properties['if-match']});
    const responses = { 200: { description: 'OK', content: { 'application/json': { schema: success } } } };
    for (const code of [...new Set(Object.values(errors))]) responses[code] = { description: Object.entries(errors).filter(([, status]) => status === code).map(([name]) => name).join(', '), content: { 'application/json': { schema: error } } };
    paths[path] ??= {};
    paths[path][route.method.toLowerCase()] = { operationId: route.operation, summary: route.operation, security: route.public?(route.csrf?[{refreshCookie:[],csrfHeader:[]}]:[]):[{ bearerAuth: [] }], 'x-roles': route.roles, 'x-idempotency': route.keyed ? 'principal + method + canonical path + key; request digest; 24 hours; session issuance replays only while original credentials remain usable' : route.method==='GET'?'read-only':route.method==='DELETE'?'natural idempotency':'single-use rotating credential; replay revokes session', parameters, ...(route.body ? { requestBody: { required: true, content: { 'application/json': { schema: route.body } } } } : {}), responses };
    if(route.conditionalAdmin){paths[path][route.method.toLowerCase()].security=[{}, {bearerAuth:[]}];paths[path][route.method.toLowerCase()]['x-body-authorization']='Without role: public student registration. With role: authenticated admin required; student gradeId must exist, admin gradeId must be null.';}
    if(route.conditionalVersion)paths[path][route.method.toLowerCase()]['x-version-precondition']='If-Match contains the quoted current version, e.g. "1". Already deleted resources return the existing tombstone.';
  }
  return { openapi: '3.1.0', info: { title: 'Exam platform API', version: '0.1.0', description: '当前已登记的考试和身份资源接口；未登记能力默认拒绝。' }, paths, components: { securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer' },refreshCookie:{type:'apiKey',in:'cookie',name:'__Host-exam_refresh'},csrfHeader:{type:'apiKey',in:'header',name:'X-CSRF-Token'} } } };
}
const destination = new URL('../../docs/api/openapi.yaml', import.meta.url);
const output = JSON.stringify(document(), null, 2) + '\n';
if (process.argv.includes('--write')) { await mkdir(new URL('.', destination), { recursive: true }); await writeFile(destination, output); }
else {
  if (await readFile(destination, 'utf8') !== output) throw new Error('CT02/CT03: OpenAPI 与已注册路由不一致；运行 npm run api:write');
  console.log('CT02/CT03 OpenAPI: passed');
}
