// The native UI never chooses a tab/frame from untrusted page-provided identifiers.
let vaultTarget = null;
let vaultPending = null;
const vaultPost = m => { try { if(port)port.postMessage(m); } catch (_) {} };
browser.runtime.onMessage.addListener((m,sender) => {
  if(m.type!=='vaultFocus' || !sender.tab || !Number.isInteger(sender.frameId) ||
     !/^https?:\/\//.test(sender.url || '') || typeof m.token!=='string' || m.token.length>80)return;
  if(!m.visible){
    if(vaultTarget?.token===m.token){vaultTarget=null;vaultPost({type:'vaultFocus',visible:false});}
    return;
  }
  vaultTarget={tabId:sender.tab.id,frameId:sender.frameId,url:sender.url,top:sender.tab.url,token:m.token};
  vaultPost({type:'vaultFocus',visible:true,token:m.token,url:sender.url,top:sender.tab.url,
    y:sender.frameId===0 && Number.isFinite(m.y)?m.y:0.7});
});
async function handleVaultNative(m) {
  if(m.type==='vaultCancel') {
    const p=vaultPending;vaultPending=null;
    if(p)browser.tabs.sendMessage(p.tabId,{type:'vaultCancel'},{frameId:p.frameId}).catch(()=>{});
    return;
  }
  if(m.type==='vaultArm') {
    const t=vaultTarget;vaultPending=null;
    try {
      if(!t || t.token!==m.token || (await browser.tabs.get(t.tabId)).url!==t.top)throw Error();
      const result=await browser.tabs.sendMessage(t.tabId,{type:'vaultArm',token:t.token},{frameId:t.frameId});
      if(!result?.ok || vaultTarget?.token!==t.token)throw Error();
      vaultPending={...t,expires:Date.now()+180000};
      vaultPost({type:'vaultArmed',token:t.token,url:t.url});
    }catch(_){vaultPost({type:'vaultResult',ok:false});}
    return;
  }
  if(m.type==='vaultFill') {
    const p=vaultPending;vaultPending=null;
    try {
      if(!p || p.token!==m.token || Date.now()>p.expires || (await browser.tabs.get(p.tabId)).url!==p.top)throw Error();
      const result=await browser.tabs.sendMessage(p.tabId,{type:'vaultFill',token:p.token,account:m.account,password:m.password},{frameId:p.frameId});
      vaultPost({type:'vaultResult',ok:result?.ok===true});
    }catch(_){vaultPost({type:'vaultResult',ok:false});}
  }
}
