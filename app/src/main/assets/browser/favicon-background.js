// Fetch only website-provided icons (or /favicon.ico); no third-party icon service.
let iconGeneration=0;
const iconControllers=new Set();
function clearFaviconJobs(){iconGeneration++;iconJobs.length=0;for(const controller of iconControllers)controller.abort();iconControllers.clear();}
const iconJobs=[];
let iconWorkers=0;
browser.runtime.onMessage.addListener((m,sender)=>{
  if(m.type==='pageIcon' && sender.frameId===0 && /^https?:\/\//.test(m.icon||'')){
    try{if(port)port.postMessage({type:'pageIcon',url:sender.url,icon:m.icon});}catch(_){}
  }
});
function requestFavicon(m){
  if(!/^https?:\/\//.test(m.url||'') || !/^https?:\/\//.test(m.icon||'') || iconJobs.length>=200)return;
  iconJobs.push({url:m.url,icon:m.icon,epoch:m.epoch,generation:iconGeneration});pumpIcons();
}
function pumpIcons(){
  while(iconWorkers<2 && iconJobs.length){
    iconWorkers++;const job=iconJobs.shift();
    renderIcon(job.icon).then(data=>{try{if(port&&job.generation===iconGeneration)port.postMessage({type:'favicon',url:job.url,epoch:job.epoch,data});}catch(_){}},()=>{try{if(port&&job.generation===iconGeneration)port.postMessage({type:"faviconFailed",url:job.url,epoch:job.epoch});}catch(_){}})
      .finally(()=>{iconWorkers--;pumpIcons();});
  }
}
async function renderIcon(url){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),5000);
  iconControllers.add(controller);
  try{
    const response=await fetch(url,{cache:'no-store',credentials:'omit',referrerPolicy:'no-referrer',signal:controller.signal});
    if(!response.ok)throw Error();
    const reader=response.body.getReader(),chunks=[];let total=0;
    while(true){const {done,value}=await reader.read();if(done)break;total+=value.length;if(total>524288){await reader.cancel();throw Error();}chunks.push(value);}
    const objectURL=URL.createObjectURL(new Blob(chunks,{type:response.headers.get('content-type')||'image/x-icon'}));
    try{
      const img=new Image();
      await new Promise((resolve,reject)=>{
        const timeout=setTimeout(()=>{img.src='';reject(Error());},4000);
        img.onload=()=>{clearTimeout(timeout);resolve();};img.onerror=()=>{clearTimeout(timeout);reject(Error());};img.src=objectURL;
      });
      if(!img.naturalWidth||!img.naturalHeight)throw Error();
      const canvas=document.createElement('canvas');canvas.width=64;canvas.height=64;
      const scale=Math.min(64/img.naturalWidth,64/img.naturalHeight),w=img.naturalWidth*scale,h=img.naturalHeight*scale;
      canvas.getContext('2d').drawImage(img,(64-w)/2,(64-h)/2,w,h);
      return canvas.toDataURL('image/png');
    }finally{URL.revokeObjectURL(objectURL);}
  }finally{clearTimeout(timer);iconControllers.delete(controller);}
}
