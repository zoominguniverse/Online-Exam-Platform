import { createServer } from 'node:net'
import { randomBytes } from 'node:crypto'

// Use dynamic ports above browser/fetch's blocked-port list, including SANE port 6566.
export async function freePort() {
  for(let attempt=0;attempt<40;attempt++) {
    const port=49152+randomBytes(2).readUInt16BE()%16384
    const server=createServer()
    try {await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve)})}
    catch(error){if(['EADDRINUSE','EACCES'].includes(error.code))continue;throw error}
    await new Promise(resolve=>server.close(resolve));return port
  }
  throw new Error('Could not allocate an available browser-safe dynamic port')
}
