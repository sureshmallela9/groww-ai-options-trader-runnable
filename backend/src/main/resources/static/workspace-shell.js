(function(root){
  'use strict';
  const screens=root.TradingRoutes?.routes||(typeof module==='object'&&module.exports?require('./workspace-router.js').routes:{});
  function screenName(value){return Object.hasOwn(screens,value)?value:'overview';}
  function dailyLossLeverage(risk){return risk.dailyLossLeverage??(risk.mode==='REAL'?(risk.intraday?.realMaxLeverage??1):(risk.intraday?.paperLeverage??1));}
  function dailyLossBase(risk){return risk.dailyLossExposureBase??Math.min(risk.policy.capital,risk.policy.maxPositionValue??risk.policy.capital)*dailyLossLeverage(risk);}
  function perTradeRiskLimit(risk){return risk.riskPerTradeLimit??(risk.riskExposureBase??dailyLossBase(risk))*risk.policy.riskPerTradePercent/100;}
  function dailyLossLimit(risk){return risk.dailyLossLimit??dailyLossBase(risk)*risk.policy.dailyLossPercent/100;}
  function humanState(value){const names={BROKER_PROTECTION_FAILURE:'Broker stop protection failed',INTRADAY_CHECK:'Checking Groww intraday / MIS eligibility',NEWS_ANALYSIS:'Researching market news',NEWS_WAIT:'Waiting for news evidence',CHECKING_RULES:'Checking session and rules',RISK_CHECK:'Rechecking entry risk',STARTING:'Starting checks',VERIFYING_DATA:'Validating market data',ANALYZING:'Analysing stocks',FINAL_DECISION:'Checking the final decision',POSITION_OPEN:'Position open',MONITORING_POSITION:'Monitoring a position',NO_TRADE:'No qualifying trade',STOPPED:'Automation stopped',PAUSED:'Automation paused',CALENDAR_MISSING:'Session calendar missing',ENTRY_WINDOW_CLOSED:'Entry window closed',MARKET_CLOSED:'Market closed',WAITING_FOR_SESSION:'Waiting for the session',DATA_UNAVAILABLE:'Waiting for data',EVENT_RISK:'Scheduled event restriction',DAILY_LIMIT:'Daily limit reached',BLOCKED:'Entries blocked',BEFORE_OPEN:'Waiting for market open',NON_TRADING_DAY:'Non-trading day',REJECTED:'Entry rejected'};return names[value]||String(value||'Not started').toLowerCase().replaceAll('_',' ');}
  if(typeof module==='object'&&module.exports){module.exports={screenName,humanState,dailyLossLimit,dailyLossBase,dailyLossLeverage,perTradeRiskLimit};return;}
  if(!document.body.hasAttribute('data-workspace'))return;
  const $=id=>document.getElementById(id);let latest=null,policyDirty=false,analysisKey='',previousRoute=null,connection={state:'disconnected'},appliedConnection=null;
  function syncPosition(route){
    if(route?.name!=='positions'||!route.id)return;
    const found=root.AutoPaperUI.showPosition(route.id);
    if(found===false){$('workspace-position-content').textContent='This position was not found in the loaded history.';if(!$('workspace-position-dialog').open)$('workspace-position-dialog').showModal();}
  }
  function renderRoute(route,changed){
    const name=route.name;
    if(previousRoute?.name==='reports'&&name!=='reports')root.WorkspaceReports.leave();
    if(previousRoute?.name==='tools'&&name!=='tools')root.WorkspaceResearch.leave();
    for(const panel of document.querySelectorAll('[data-screen]'))panel.hidden=panel.dataset.screen!==name;
    for(const link of document.querySelectorAll('nav [data-screen-target]')){if(link.dataset.screenTarget===name)link.setAttribute('aria-current','page');else link.removeAttribute('aria-current');}
    const definition=root.TradingRoutes.routes[name]||{title:'Screen not found',description:'Choose a workspace screen from the navigation.'};
    $('screen-title').textContent=definition.title;$('screen-description').textContent=definition.description;
    document.title=definition.title+' · Trading Workspace';
    if(!(name==='positions'&&route.id)&&$('workspace-position-dialog').open)$('workspace-position-dialog').close();
    if(changed){root.scrollTo({top:0,behavior:'instant'});$('screen-title').focus({preventScroll:true});}
    previousRoute=route;
    if(name==='reports')root.WorkspaceReports.enter(route);
    if(name==='audit')root.GrowwApiAudit?.enter();else root.GrowwApiAudit?.leave();
    if(name==='tools')root.WorkspaceResearch.enter();
    if(name==='news')root.WorkspaceNews?.enter();else root.WorkspaceNews?.leave();
    if(name==='automation')root.ShortlistCharts?.enter();else root.ShortlistCharts?.leave();
    if(['risk','tools'].includes(name))root.refresh();
    if(name==='overview'){root.refreshMarket();root.AutoPaperUI.refresh();}
    syncPosition(route);
  }
  const router=root.TradingRoutes.createRouter({location:root.location,history:root.history,onChange:renderRoute});
  function navigate(name,id=null){router.navigate(name,id);}
  document.addEventListener('click',event=>{
    const target=event.target.closest('[data-screen-target]');
    if(target&&!event.defaultPrevented&&event.button===0&&!event.ctrlKey&&!event.metaKey&&!event.shiftKey&&!event.altKey){event.preventDefault();navigate(target.dataset.screenTarget,target.dataset.routeId||null);}
    const close=event.target.closest('[data-close-dialog]');if(close)$(close.dataset.closeDialog).close();
  });
  root.addEventListener('popstate',()=>router.sync());root.addEventListener('hashchange',()=>router.sync());
  $('workspace-connect').onclick=()=>$('workspace-connect-dialog').showModal();
  $('mode-paper').onclick=()=>{$('mode-paper').setAttribute('aria-pressed','true');$('mode-real').setAttribute('aria-pressed','false');};
  $('mode-real').onclick=()=>{$('realModeDialog').showModal();};
  function renderActivity(){
    root.SystemFlowWindow?.publish(latest,connection);
    root.AutomationActivity.render($('overview-automation-activity'),latest?.automation,{compact:true,connection});
    root.AutomationActivity.render($('workspace-automation-progress'),latest?.automation,{connection});
    const a=latest?.automation;
    $('automation-status-response').textContent=a?JSON.stringify({state:a.state,enabled:a.enabled,busy:a.busy,message:a.message,activity:a.activity,
      selectionStatus:a.selection?.status,decision:a.decision?{action:a.decision.action,symbol:a.decision.symbol,reasons:a.decision.reasons}:null},null,2):'Connect to load status.';
    const phase=a?.activity?.phase||a?.state;
    const stage=connection.state==='connected'?{VERIFYING_DATA:0,ANALYZING:1,FINAL_DECISION:3,RISK_CHECK:3,POSITION_OPEN:4,MONITORING_POSITION:4}[phase]:undefined;
    for(const [i,element]of [...$('workspace-pipeline').children].entries()){
      element.classList.toggle('complete',stage!==undefined&&i<stage);
      element.classList.toggle('current',stage===i);
    }
  }
  $('automation-refresh-status').onclick=()=>root.AutoPaperUI.refresh();
  function notice(value){
    connection=value;root.WorkspaceNews?.connection(value);renderActivity();const connected=value.state==='connected';
    $('workspace-connection-status').textContent=connected?'● Workspace connected':value.state==='connecting'?'Connecting…':value.state==='error'?'Connection needs attention':'Workspace not connected';
    $('workspace-notice').hidden=connected;
    $('workspace-notice-text').textContent=value.state==='error'?value.message+' Last displayed positions and P&L may be stale.':value.state==='connecting'?'Connecting to the paper trading service…':'Connect to load automation, positions and trading limits. Research and order controls require a connection.';
    $('workspace-retry').hidden=!['error'].includes(value.state)||value.status===401;
    $('workspace-connect').textContent=connected?'Connection':'Connect workspace';
    if(connected!==appliedConnection){for(const element of document.querySelectorAll('[data-requires-connection]'))if(!element.dataset.busy)element.disabled=!connected;appliedConnection=connected;if(connected)$('research-message').textContent='Workspace connected. Load market context and the maintained stock list to begin research.';}
    if(value.updatedAt)$('workspace-last-update').textContent='Updated '+new Date(value.updatedAt).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata'})+' IST';
    if(connected&&$('workspace-connect-dialog').open)$('workspace-connect-dialog').close();
    if(!connected)$('research-submit').disabled=true;
  }
  for(const id of ['auto-start','auto-stop','auto-exit','auto-event','workspace-kill','workspace-reset-kill'])$(id).setAttribute('data-requires-connection','');
  $('workspace-policy-form').querySelector('button[type=submit]').setAttribute('data-requires-connection','');
  root.AutoPaperUI.subscribeConnection(notice);
  $('workspace-retry').onclick=()=>root.AutoPaperUI.refresh();
  $('workspace-disconnect').onclick=()=>{root.AutoPaperUI.disconnect();latest=null;analysisKey='';$('workspace-connect-dialog').close();root.SessionAnalysisUI?.stop();root.WorkspaceResearch.reset();};
  $('workspace-position-dialog').addEventListener('close',()=>{const route=router.current();if(route.name==='positions'&&route.id)router.navigate('positions',null,{replace:true});});
  function text(parent,tag,value){const el=document.createElement(tag);el.textContent=value;parent.append(el);return el;}
  function rows(container,values){container.replaceChildren();for(const [label,value]of values){const row=text(container,'div','');row.className='rule-row';text(row,'span',label);text(row,'strong',value);}}
  const money=value=>new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(Number(value||0));
  const fields=[['capital','Allocated capital (₹)',1,10000000,.01],['riskPerTradePercent','Risk per trade (% of intraday exposure)',.01,2,.01],['dailyLossPercent','Daily loss limit (% of intraday exposure)',.01,10,.01],['maxOpenPositions','Maximum active positions',1,1,1],['dailyProfitTarget','Daily profit exit (₹, 0 = off)',0,10000000,.01],['maxTradesPerDay','Entries per day',1,20,1],['maxConsecutiveLosses','Consecutive loss limit',1,10,1],['maxSpreadPercent','Maximum spread (%)',.01,1,.01],['maxSlippagePercent','Maximum slippage (%)',0,1,.01]];
  for(const [key,label,min,max,step]of fields){const wrapper=text($('workspace-policy-fields'),'label',label);wrapper.className='field-label';const input=document.createElement('input');input.id='policy-'+key;input.type='number';input.required=true;input.min=min;input.max=max;input.step=step;wrapper.append(input);input.oninput=()=>{policyDirty=true;};}
  for(const input of $('settings').querySelectorAll('input'))input.addEventListener('input',()=>{root.workspaceSettingsDirty=true;});
  root.AutoPaperUI.subscribe(value=>{
    latest=value;const {automation:a,portfolio:p,risk:r}=value;
    renderActivity();
    const open=(p.positions||[]).filter(row=>['POSITION_OPEN','EXIT_PENDING'].includes(row.state)||(row.state==='ENTRY_PENDING'&&row.remainingQuantity>0));
    $('overview-position').textContent=open.length?open[0].symbol+' · '+open[0].remainingQuantity+' share(s) · '+humanState(open[0].state)+' · '+money(open[0].unrealizedPnl)+' open P&L':'No open managed position. A qualifying signal and risk approval are required for entry.';
    $('auto-open-count').textContent=String(open.length);
    rows($('workspace-session'),[['Session',r.calendar.verified?(r.calendar.sessionOpen?'Market open':'Market closed'):'Not verified'],['Entry window',r.calendar.open&&r.calendar.entryEndsAt?r.calendar.open+'–'+r.calendar.entryEndsAt+' IST':'Not available'],['Next session',r.calendar.nextEntrySession||'Not configured'],['Capital',money(r.policy.capital)],['Risk per entry',r.policy.riskPerTradePercent+'% · '+money(perTradeRiskLimit(r))],['Daily entry limit',r.policy.maxTradesPerDay],['Daily loss limit',money(dailyLossLimit(r))]]);
    rows($('workspace-risk-summary'),[['Capital',money(r.policy.capital)],['Risk per entry',r.policy.riskPerTradePercent+'% · '+money(perTradeRiskLimit(r))],['Daily loss percentage',r.policy.dailyLossPercent+'% of configured exposure'],['Daily loss cash allocation',money(r.dailyLossCashAllocation??Math.min(r.policy.capital,r.policy.maxPositionValue??r.policy.capital))],['Daily loss leverage',dailyLossLeverage(r)+'×'],['Daily loss exposure base',money(dailyLossBase(r))],['Effective daily loss limit',money(dailyLossLimit(r))],['Daily loss lock',r.dailyLossLatched?'New entries blocked for today':'Not triggered'],['Cash allocation cap before leverage',money(r.policy.maxPositionValue)],['Open position limit',r.policy.maxOpenPositions],['Daily entry limit',r.policy.maxTradesPerDay],['Consecutive loss limit',r.policy.maxConsecutiveLosses],['Spread limit',r.policy.maxSpreadPercent+'%'],['Quote age limit',r.policy.maxQuoteAgeSeconds+' seconds']]);
    $('workspace-kill-state').textContent=r.killSwitch?'Kill switch active. New entries are blocked.':'Kill switch is off. Normal entry and risk rules apply.';
    if(!policyDirty)for(const [key]of fields)$('policy-'+key).value=r.policy[key];
    const key=JSON.stringify([a.state,a.message,a.decision?.decisionId,a.selection?.selectionId,open[0]?.markedAt,open[0]?.lastAiReceivedAt,open[0]?.state]);
    if(key!==analysisKey){analysisKey=key;root.TradingAnalysisView.render($('workspace-readable-analysis'),{...a,activePosition:open[0]});}
    syncPosition(router.current());
  });
  async function action(button,work){button.disabled=true;try{if(!latest)throw Error('Connect the workspace first.');await work();}catch(error){$('workspace-policy-message').textContent=error.message;root.toast?.(error.message);}finally{button.disabled=false;}}
  $('workspace-policy-form').onsubmit=event=>{event.preventDefault();const button=event.submitter||$('workspace-policy-form').querySelector('button');action(button,async()=>{
    const policy={...latest.risk.policy};for(const [key]of fields)policy[key]=Number($('policy-'+key).value);
    const admin=$('workspace-admin-token').value;try{await root.AutoPaperUI.updatePolicy(policy,admin);policyDirty=false;$('workspace-policy-message').textContent='Hard limits saved. Effective limits still include your dashboard settings.';}finally{$('workspace-admin-token').value='';}
  });};
  $('workspace-kill').onclick=()=>action($('workspace-kill'),async()=>{await root.AutoPaperUI.setKillSwitch(true);$('workspace-policy-message').textContent='Kill switch activated. Exit requests remain subject to usable quotes.';});
  $('workspace-reset-kill').onclick=()=>action($('workspace-reset-kill'),async()=>{const admin=$('workspace-admin-token').value;try{await root.AutoPaperUI.setKillSwitch(false,admin);$('workspace-policy-message').textContent='Kill switch reset. Other entry checks still apply.';}finally{$('workspace-admin-token').value='';}});
  async function services(){try{const data=await root.WorkspaceAPI.request('/api/ai/status');const compute=data.health?.compute;
    $('workspace-ai-status').textContent=data.ready?'Trading AI ready'+(compute?' · '+(compute.gpuReady?'GPU batches ready':compute.fallbackReason==='WINDOWS_APPLICATION_CONTROL_BLOCKED'?'CPU · GPU blocked by Windows':'CPU'):''):data.connected?'Trading AI needs attention':'Trading AI unavailable';
    $('workspace-ai-status').title=compute?.gpuReady?(compute.device+' · GPU handles batched EMA/MACD; small requests and other checks use CPU.'):compute?.fallbackReason==='WINDOWS_APPLICATION_CONTROL_BLOCKED'?'Windows Application Control blocked the CUDA extension. CPU analysis remains available.':compute?.fallbackReason||'';}catch{$('workspace-ai-status').textContent='Trading AI status unavailable';}}
  root.WorkspaceShell={navigate,current:()=>router.current(),humanState,openPosition(id){navigate('positions',id);},openManualAnalysis(){navigate('analysis');$('manual-analysis').open=true;}};
  router.start();services();setInterval(()=>{if(!document.hidden)services();},15000);
  document.addEventListener('visibilitychange',()=>{if(!document.hidden){services();root.AutoPaperUI.refresh();if(router.current().name==='overview')root.refreshMarket();}});
})(globalThis);
