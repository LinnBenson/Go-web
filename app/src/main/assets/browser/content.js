(() => {
  // Define a device-width layout viewport without fixing document height or
  // replacing the site's DOM. Full-page CSS zoom scales controls and images too.
  let zoom = 70;
  let applying = false;
  let observedHead = null;
  let settled = false;
  function apply() {
    if (applying || !document.documentElement) return;
    applying = true;
    if (document.head) {
      if (!settled && observedHead !== document.head) {
        observedHead = document.head;
        observer.disconnect();
        // Body parsing does not affect viewport metadata; avoid work on every body mutation.
        observer.observe(observedHead, {childList:true, subtree:true});
      }
      let meta = document.querySelector('meta[name="viewport" i]');
      if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; document.head.prepend(meta); }
      const value = 'width=device-width, initial-scale=1, minimum-scale=0.25, maximum-scale=5, user-scalable=yes';
      if (meta.content !== value) meta.content = value;
    }
    const scale = String(zoom / 100);
    if (document.documentElement.style.getPropertyValue('zoom') !== scale) document.documentElement.style.setProperty('zoom', scale);
    applying = false;
  }
  const observer = new MutationObserver(() => apply());
  observer.observe(document, {childList:true, subtree:true});
  document.addEventListener('DOMContentLoaded', () => { settled=true; apply(); observer.disconnect(); }, {once:true});
  browser.runtime.sendMessage({type:'config'}).then(c => { zoom=c.zoom; apply(); }).catch(() => apply());
  browser.runtime.onMessage.addListener(m => { if(m.type==='zoom') { zoom=m.zoom; apply(); } });
  apply();
  // Read-only viewport bounds for native round-screen spacers; never modify site DOM or APIs.
  let boundsFrame = 0;
  let lastBounds = null;
  function reportBounds() {
    if (boundsFrame || document.hidden) return;
    boundsFrame = setTimeout(() => {
      boundsFrame = 0;
      const el = document.scrollingElement;
      if (!el) return;
      const viewport = window.visualViewport;
      const bottom = viewport ? viewport.pageTop + viewport.height : window.scrollY + window.innerHeight;
      const height = el.scrollHeight;
      const atBottom = bottom >= height - 3;
      const key = location.href + ':' + atBottom;
      if (lastBounds === key) return;
      lastBounds = key;
      browser.runtime.sendMessage({type:'pageBounds', url:location.href, bottom:atBottom}).catch(() => { lastBounds = null; });
    }, 80);
  }
  addEventListener('scroll', reportBounds, {passive:true});
  addEventListener('resize', reportBounds, {passive:true});
  addEventListener('pageshow', () => { lastBounds = null; reportBounds(); });
  addEventListener('pagehide', () => { clearTimeout(boundsFrame); boundsFrame = 0; });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { clearTimeout(boundsFrame); boundsFrame = 0; }
    else { lastBounds = null; reportBounds(); }
  });
  if (window.visualViewport) {
    window.visualViewport.addEventListener('scroll', reportBounds, {passive:true});
    window.visualViewport.addEventListener('resize', reportBounds, {passive:true});
  }
  document.addEventListener('DOMContentLoaded', () => {
    new ResizeObserver(reportBounds).observe(document.documentElement);
    reportBounds();
  }, {once:true});
})();
