import { deflateSync } from 'node:zlib'
export function png(width=1,height=1) {
  if(!Number.isInteger(width)||!Number.isInteger(height)||width<1||height<1||width>512||height>512)throw new Error('Invalid fixture image size')
  function chunk(name,data){const type=Buffer.from(name);const length=Buffer.alloc(4);length.writeUInt32BE(data.length);let crc=0xffffffff;for(const byte of Buffer.concat([type,data])){crc^=byte;for(let i=0;i<8;i++)crc=(crc>>>1)^((crc&1)?0xedb88320:0)}const checksum=Buffer.alloc(4);checksum.writeUInt32BE((crc^0xffffffff)>>>0);return Buffer.concat([length,type,data,checksum])}
  const header=Buffer.alloc(13);header.writeUInt32BE(width,0);header.writeUInt32BE(height,4);header[8]=8;header[9]=6
  const pixels=Buffer.alloc(height*(width*4+1));for(let y=0;y<height;y++)for(let x=0;x<width;x++){const at=y*(width*4+1)+1+x*4;const light=(Math.floor(x/12)+Math.floor(y/12))%2;pixels.set(light?[213,229,249,255]:[20,60,120,255],at)}
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',deflateSync(pixels)),chunk('IEND',Buffer.alloc(0))])
}
