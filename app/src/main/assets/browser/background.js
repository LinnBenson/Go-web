/* Keep HTTP semantics in Gecko: TLS, cookies, redirects, CSP, compression and
 * streaming stay in the browser engine. Only cache lifetime is changed. */
let config = { home: 'https://google.com/', siteSettings: {} };
function zoomFor(url) {
  try { const value = config.siteSettings[new URL(url).hostname.toLowerCase()]?.zoom;
    return Number.isFinite(value) ? Math.max(20, Math.min(200, Math.round(value / 10) * 10)) : 70;
  } catch (_) { return 70; }
}
const homeTabs = new Set();
const homeRedirects = new Set();
browser.tabs.onRemoved.addListener(tabId => homeTabs.delete(tabId));
const staticTypes = new Set(['main_frame', 'stylesheet', 'script', 'image', 'font', 'web_manifest']);
// Fetch/preload/object requests do not always carry an image/font destination.
// Require a static filename AND a compatible response MIME for these fallback cases.
function isStaticResource(d) {
  if (d.type === 'script') {
    try {
      const params = new URL(d.url).searchParams;
      if (params.has('callback') || params.has('jsonp')) return false;
    } catch (_) { /* Standard script destination remains supported. */ }
  }
  if (staticTypes.has(d.type)) return true;
  if (!['xmlhttprequest', 'other', 'object'].includes(d.type)) return false;
  let path;
  try { path = new URL(d.url).pathname.toLowerCase(); } catch (_) { return false; }
  const mime = ((d.responseHeaders || []).find(h => h.name.toLowerCase() === 'content-type')?.value || '').split(';')[0].trim().toLowerCase();
  if (/\.(svg|png|jpe?g|gif|webp|avif|ico|bmp)$/.test(path)) return mime.startsWith('image/');
  if (/\.(woff2?|ttf|otf|eot)$/.test(path)) return mime.startsWith('font/') ||
    /^(application\/(font-woff|x-font-woff|x-font-ttf|x-font-opentype|vnd\.ms-fontobject|octet-stream))$/.test(mime);
  if (/\.css$/.test(path)) return mime === 'text/css';
  if (/\.m?js$/.test(path)) return ['text/javascript','application/javascript','application/x-javascript'].includes(mime);
  if (/\.wasm$/.test(path)) return ['application/wasm','application/octet-stream'].includes(mime);
  return false;
}
const normalized = value => { try { const u = new URL(value); u.hash = ''; return u.href; } catch (_) { return value; } };
const all = {urls: ['http://*/*', 'https://*/*']};
browser.webRequest.onBeforeRequest.addListener(d => {
  if (d.type === 'main_frame') {
    if (normalized(d.url) === normalized(config.home) || homeRedirects.has(d.requestId)) homeTabs.add(d.tabId);
    else homeTabs.delete(d.tabId);
  }
}, all);
browser.webRequest.onBeforeRedirect.addListener(d => {
  if (d.type === 'main_frame' && homeTabs.has(d.tabId)) homeRedirects.add(d.requestId);
}, all);
const end = d => homeRedirects.delete(d.requestId);
browser.webRequest.onCompleted.addListener(end, all);
browser.webRequest.onErrorOccurred.addListener(end, all);
browser.webRequest.onHeadersReceived.addListener(d => {
  // Leave APIs, POST, JSONP, errors and non-resource responses entirely untouched.
  // Do not intercept bodies or replace fetch/XHR; JS executes through Gecko normally.
  // Ordinary pages retain their original HTTP cache policy and validators.
  if (!homeTabs.has(d.tabId)) return {};
  if (d.method !== 'GET' || d.statusCode < 200 || d.statusCode >= 400 || !isStaticResource(d)) return {};
  const headers = (d.responseHeaders || []).filter(h => !['cache-control','expires','pragma','age'].includes(h.name.toLowerCase()));
  headers.push({name: 'Cache-Control', value: 'private, max-age=315360000, immutable'});
  return {responseHeaders: headers};
}, all, ['blocking', 'responseHeaders']);
browser.runtime.onMessage.addListener((message, sender) => {
  if (message.type === 'config') return Promise.resolve({zoom:zoomFor(sender.url || sender.tab?.url)});
  if (message.type === 'pageBounds' && sender.frameId === 0 && port) { try { port.postMessage(message); } catch (_) {} }
});
// The built-in extension can start before Android registers its message delegate.
// Recover that cold-start race instead of leaving a permanently disconnected port.
let port = null;
let reconnectTimer = null;
let reconnectDelay = 100;
function scheduleReconnect() {
  if (reconnectTimer !== null) return;
  reconnectTimer = setTimeout(() => { reconnectTimer = null; connectNative(); }, reconnectDelay);
  reconnectDelay = Math.min(2000, reconnectDelay * 2);
}
async function configureFromNative(message, connection) {
  try {
    if (message.type === 'configure' && connection === port) {
      const changed = config.home !== message.home;
      config = {home: message.home, siteSettings: message.siteSettings || config.siteSettings};
      if(message.siteHost){
        if(message.siteProfile)config.siteSettings[message.siteHost]=message.siteProfile;
        else delete config.siteSettings[message.siteHost];
      }
      if (message.clearCache) await browser.browsingData.removeCache({});
      if (changed) homeTabs.clear();
      if (connection !== port) return;
      reconnectDelay = 100;
      connection.postMessage({type:'configured', action:message.action || ''});
      if (message.action === 'zoom') {
        const tabs = await browser.tabs.query({});
        for (const tab of tabs) browser.tabs.sendMessage(tab.id, {type:'zoom', zoom:zoomFor(tab.url)}).catch(() => {});
      }
    }
  } catch (error) {
    if (connection === port) { try { connection.postMessage({type:'error', message:String(error)}); } catch (_) {} }
  }
}
function connectNative() {
  try {
    const connection = browser.runtime.connectNative('goapp');
    port = connection;
    connection.onMessage.addListener(message => {
      if(connection!==port)return;
      if(message.type==="faviconRequest"){requestFavicon(message);return;}
      if(message.type==="faviconClear"){clearFaviconJobs();return;}
      if(message.type?.startsWith("vault") && typeof handleVaultNative === "function")return handleVaultNative(message);
      else return configureFromNative(message, connection);
    });
    connection.onDisconnect.addListener(() => {
      if (port !== connection) return;
      port = null;
      scheduleReconnect();
    });
    // Send ready only after both listeners are installed.
    connection.postMessage({type:'ready'});
  } catch (_) { port = null; scheduleReconnect(); }
}
connectNative();
