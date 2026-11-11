const messages=new Map<HTMLElement,HTMLElement>()
let observer:MutationObserver|undefined
function watchRemoval() {
  if(observer)return
  observer=new MutationObserver(()=>{for(const [field,message] of messages)if(!field.isConnected){message.remove();messages.delete(field)}if(!messages.size){observer?.disconnect();observer=undefined}})
  observer.observe(document.body,{childList:true,subtree:true})
}
function control(event:Event) {
  const value=event.target
  return (value instanceof HTMLInputElement||value instanceof HTMLSelectElement||value instanceof HTMLTextAreaElement)
    &&value.closest('form')&&value.willValidate?value:null
}
export function validateField(event:Event) {
  const field=control(event);if(!field)return
  const previous=field.dataset.validationMessage
  if(field.validity.valid){clearField(event);return}
  field.setAttribute('aria-invalid','true')
  const id=previous||'exam-field-'+crypto.randomUUID();let message=document.getElementById(id)
  if(!message){message=document.createElement('span');message.id=id;message.className='field-error';message.setAttribute('aria-live','polite');(field.closest('label')||field).insertAdjacentElement('afterend',message);field.dataset.validationMessage=id;field.setAttribute('aria-describedby',`${field.getAttribute('aria-describedby')||''} ${id}`.trim())}
  message.textContent=field.validity.valueMissing?'请填写此项。':field.validity.typeMismatch?'请输入有效格式。':'请按字段要求填写有效值。'
  messages.set(field,message);watchRemoval()
}
export function clearField(event:Event) {
  const field=control(event);if(!field||!field.validity.valid)return
  const id=field.dataset.validationMessage;if(id){document.getElementById(id)?.remove();const descriptions=(field.getAttribute('aria-describedby')||'').split(/\s+/).filter(value=>value&&value!==id);if(descriptions.length)field.setAttribute('aria-describedby',descriptions.join(' '));else field.removeAttribute('aria-describedby');delete field.dataset.validationMessage}
  field.removeAttribute('aria-invalid')
  messages.delete(field);if(!messages.size){observer?.disconnect();observer=undefined}
}
