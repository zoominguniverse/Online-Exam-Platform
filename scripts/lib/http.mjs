export class Api {
  constructor(origin,{fresh=false}={}){this.origin=origin;this.cookies=new Map();this.fresh=fresh}
  async raw(method,path,data,headers={}) {
    const url=this.origin+'/api/v1'+path
    const options={method,headers:{...(this.fresh?{Connection:'close'}:{}),...Object.fromEntries(this.cookies.size?[['Cookie',[...this.cookies].map(([name,value])=>name+'='+value).join('; ')]]:[]),...headers,...(data&&!(data instanceof FormData)?{'Content-Type':'application/json'}:{})},body:data?(data instanceof FormData?data:JSON.stringify(data)):undefined}
    let response
    if(this.fresh) {
      const encoded=new Request(url,options);const bytes=Buffer.from(await encoded.arrayBuffer())
      response=await new Promise((resolve,reject)=>{
        const outgoing=(url.startsWith('https:')?httpsRequest:httpRequest)(url,{method,headers:{...Object.fromEntries(encoded.headers),...(options.body?{'Content-Length':String(bytes.length)}:{})},agent:false},incoming=>{
          const parts=[];incoming.on('data',part=>parts.push(part));incoming.on('end',()=>{const resultHeaders=new Headers();for(let i=0;i<incoming.rawHeaders.length;i+=2)resultHeaders.append(incoming.rawHeaders[i],incoming.rawHeaders[i+1]);resolve(new Response(Buffer.concat(parts),{status:incoming.statusCode,headers:resultHeaders}))});incoming.on('error',reject)
        });outgoing.on('error',reject);outgoing.setTimeout(40000,()=>outgoing.destroy(new Error('Request timeout')));outgoing.end(bytes)
      })
    } else response=await fetch(url,{...options,signal:AbortSignal.timeout(40000)})
    for(const value of response.headers.getSetCookie()){const [name,...parts]=value.split(';')[0].split('=');this.cookies.set(name,parts.join('='))}
    return response
  }
  async get(path){const response=await this.raw('GET',path);if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(`GET ${path}: HTTP ${response.status}, non-JSON gateway response`);const body=await response.json();if(!response.ok)throw new Error(`GET ${path}: HTTP ${response.status} ${body.code}`);return body.data}
  async write(method,path,data,key){const csrf=await this.get('/auth/csrf');const response=await this.raw(method,path,data,{[csrf.headerName]:csrf.token,...(key?{'Idempotency-Key':key}:{})});if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(`${method} ${path}: HTTP ${response.status}, non-JSON gateway response`);const body=await response.json();if(!response.ok)throw new Error(`${method} ${path}: HTTP ${response.status} ${body.code}`);return body.data}
  async login(username,password){return this.write('POST','/auth/login',{username,password})}
}
import { request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
