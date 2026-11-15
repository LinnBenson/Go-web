(() => {
  // Credentials are only received after a native PIN unlock and explicit record selection.
  let target = null;
  let token = '';
  let armed = null;
  let timer = 0;
  const send = message => browser.runtime.sendMessage(message).catch(() => {});
  const usable = el => el instanceof HTMLInputElement && el.isConnected && !el.disabled && !el.readOnly &&
    el.getClientRects().length > 0 && (!el.checkVisibility || el.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})) && getComputedStyle(el).visibility === 'visible' && getComputedStyle(el).display !== 'none';
  const password = el => usable(el) && el.type === 'password';
  function active() {
    let el = document.activeElement;
    while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
    return el;
  }
  function report() {
    clearTimeout(timer);
    const el = active();
    if (!password(el)) {
      if (token) send({type:'vaultFocus', token, visible:false});
      target = null; token = ''; return;
    }
    if (el !== target) { target = el; token = Array.from(crypto.getRandomValues(new Uint32Array(4)),n=>n.toString(16)).join("-"); }
    const rect = el.getBoundingClientRect();
    send({type:'vaultFocus', token, visible:true, y:rect.bottom / Math.max(1,innerHeight)});
  }
  document.addEventListener('focusin', report, true);
  document.addEventListener('focusout', () => { timer = setTimeout(report,100); }, true);
  addEventListener('scroll', () => { if(target){clearTimeout(timer);timer=setTimeout(report,80);} }, {passive:true,capture:true});
  addEventListener('resize', () => { if(target)report(); });
  addEventListener('pagehide', () => { armed=null;target=null;token='';clearTimeout(timer); });
  function accountFor(pass) {
    const scope = pass.form || pass.getRootNode();
    const fields = Array.from(scope.querySelectorAll('input')).filter(el => usable(el) &&
      el.form === pass.form && ['text','email','tel','number'].includes(el.type) &&
      (el.compareDocumentPosition(pass) & Node.DOCUMENT_POSITION_FOLLOWING));
    const named = fields.filter(el => /username|email|tel|login|account|phone|mobile|user|账号|邮箱|手机/i.test(
      [el.autocomplete,el.name,el.id,el.placeholder].join(' ')) && !/otp|one-time|verification|captcha|验证码/i.test([el.autocomplete,el.name,el.id].join(' ')));
    if(named.length)return named[named.length-1];
    return fields.length===1?fields[0]:null;
  }
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
  function setValue(el,value) {
    setter.call(el,value);
    el.dispatchEvent(new Event('input',{bubbles:true,composed:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
  }
  browser.runtime.onMessage.addListener(message => {
    if(message.type==='vaultArm') {
      if(message.token!==token || !password(target))return Promise.resolve({ok:false});
      armed={token, pass:target, account:accountFor(target), url:location.href, form:target.form,
        action:target.form?.action || '', expires:Date.now()+180000};
      return Promise.resolve({ok:true});
    }
    if(message.type==='vaultCancel'){armed=null;return;}
    if(message.type!=='vaultFill')return;
    const a=armed;armed=null;
    if(!a || a.token!==message.token || Date.now()>a.expires || location.href!==a.url ||
       !password(a.pass) || a.pass.form!==a.form || (a.form?.action || '')!==a.action ||
       typeof message.password!=='string' || message.password.length>4096 || typeof message.account!=='string' || message.account.length>256)
      return Promise.resolve({ok:false});
    // Do not guess another form if the original account field was removed or changed.
    if(a.account && (!usable(a.account) || accountFor(a.pass)!==a.account))return Promise.resolve({ok:false});
    // Fill the password first; account input handlers may replace the form.
    setValue(a.pass,message.password);
    if(a.account && usable(a.account) && a.account.form===a.form)setValue(a.account,message.account);
    return Promise.resolve({ok:true});
  });
})();
