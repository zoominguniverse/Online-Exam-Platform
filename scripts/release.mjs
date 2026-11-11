import { args,compose,projectName,until } from './lib/compose.mjs'
const input=args();const project=projectName(input.project)
if(!['api-blue','api-green'].includes(input.target))throw new Error('--target must be api-blue or api-green')
const target=input.target
if(input['web-image']&&!/^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$/.test(input['web-image']))throw new Error('Invalid --web-image')
compose(project,['up','-d','--no-deps',target],input['env-file'])
await until(()=>compose(project,['exec','-T','web','wget','-q','-O','-',`http://${target}:8080/actuator/health/readiness`],input['env-file']).includes('UP'),'target readiness')
// The target is an enum above. Only our template variable is substituted; Nginx variables are preserved.
compose(project,['exec','-T','-e',`EXAM_UPSTREAM=${target}:8080`,'web','sh','-c',
  'envsubst \'${EXAM_UPSTREAM}\' < /etc/nginx/templates/default.conf.template > /tmp/exam-next.conf && cp /etc/nginx/conf.d/default.conf /tmp/exam-previous.conf && cp /tmp/exam-next.conf /etc/nginx/conf.d/default.conf && if nginx -t; then printf "%s\\n" "$EXAM_UPSTREAM" > /var/lib/exam-routing/active-upstream.tmp && mv /var/lib/exam-routing/active-upstream.tmp /var/lib/exam-routing/active-upstream && nginx -s reload; else cp /tmp/exam-previous.conf /etc/nginx/conf.d/default.conf; exit 1; fi'],input['env-file'])
const port=compose(project,['port','web','80'],input['env-file']).split(':').at(-1)
if(input['web-image']) {
  process.env.EXAM_WEB_IMAGE=input['web-image']
  compose(project,['up','-d','--no-deps','web'],input['env-file'])
}
await until(async()=>{
  const response=await fetch(`http://127.0.0.1:${port}/api/v1/version`,{headers:{Connection:'close'},signal:AbortSignal.timeout(3000)})
  await response.arrayBuffer()
  return response.ok&&response.headers.get('X-Exam-Upstream')===target+':8080'
},'edge switch confirmation')
console.log(JSON.stringify({project,active:target,switchedAt:new Date().toISOString()}))
