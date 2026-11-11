import {expect,type Page,type Locator} from '@playwright/test'

// Observe native constraints through real focus/input events; never submit a fake response.
export async function checkFormFields(page:Page){
  const fields=page.locator('main form input:not([type=file]),main form select,main form textarea')
  const checked=[]
  for(let index=0;index<await fields.count();index++){
    const field=fields.nth(index);if(!await field.isVisible()||!await field.isEnabled())continue
    const info=await field.evaluate((node:HTMLInputElement|HTMLSelectElement|HTMLTextAreaElement)=>({type:node instanceof HTMLInputElement?node.type:node.tagName.toLowerCase(),required:node.required,label:[...(node.labels||[])].map(label=>label.textContent?.trim()).join(' ')||node.getAttribute('aria-label')||node.getAttribute('aria-labelledby'),value:node.value}))
    expect(info.label,'Label for control '+index+' on '+page.url()).toBeTruthy()
    let emptied=false
    if(info.required&&['text','password','number','textarea','email','tel'].includes(info.type)){await field.fill('');emptied=true}
    if(info.required){
      const invalid=await field.evaluate((node:HTMLInputElement)=>!node.validity.valid)
      if(invalid){await field.focus();await page.keyboard.press('Tab');await expect(field).toHaveAttribute('aria-invalid','true');const id=(await field.getAttribute('aria-describedby'))!.split(/\s+/).at(-1)!;await expect(page.locator('#'+id)).toBeVisible()}
    }
    if(emptied&&info.value){await field.fill(info.value);await expect(field).not.toHaveAttribute('aria-invalid','true')}
    checked.push({label:info.label,type:info.type,required:info.required})
  }
  return{route:new URL(page.url()).pathname,fields:checked}
}

export async function failWriteAndKeepInput(page:Page,button:Locator,path:string,method='POST'){
  await expect(button).toBeVisible();await expect(button).toBeEnabled()
  const fields=page.locator('main form input:not([type=password]):not([type=file]):not([type=checkbox]):not([type=radio]),main form textarea')
  const values=await fields.evaluateAll(nodes=>nodes.map(node=>(node as HTMLInputElement).value))
  const pattern='**/api/v1'+path;let requests=0;let release=()=>{};const pending=new Promise<void>(resolve=>release=resolve)
  await page.route(pattern,async route=>{if(route.request().method()!==method){await route.continue();return}requests++;await pending;await route.abort('failed')})
  try{
    await button.click();await expect.poll(()=>requests).toBe(1);await expect(button).toBeDisabled()
    const box=await button.boundingBox();if(box)await page.mouse.click(box.x+box.width/2,box.y+box.height/2)
    await page.keyboard.press('Enter');await new Promise(resolve=>setTimeout(resolve,100));expect(requests).toBe(1)
    release();await expect(page.getByRole('alert').first()).toBeVisible();await expect(button).toBeEnabled()
    expect(await fields.evaluateAll(nodes=>nodes.map(node=>(node as HTMLInputElement).value))).toEqual(values)
    return{route:new URL(page.url()).pathname,path,method,requestsWhilePending:requests,repeatSuppressed:true,safeInputsPreserved:true}
  }finally{release();await page.unroute(pattern)}
}
