// Bookmark metadata only; images are fetched and decoded in the extension background.
function reportBookmarkIcon(){
  const links=Array.from(document.querySelectorAll('link[rel]'));
  const icon=links.find(link=>link.rel.toLowerCase().split(/\s+/).includes('icon')) || links.find(link=>link.rel.toLowerCase()==='apple-touch-icon');
  browser.runtime.sendMessage({type:'pageIcon',icon:icon?icon.href:new URL('/favicon.ico',location.href).href}).catch(()=>{});
}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',reportBookmarkIcon,{once:true});else reportBookmarkIcon();
