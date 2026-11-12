(function(root){
  'use strict';
  const activityApi=root.AutomationActivity||(typeof module==='object'&&module.exports?require('./automation-activity.js'):null);
  const defaults={intradayCheckCacheMs:60000,intradayProbeLimit:3,positionAssessmentMs:5000,scanIntervalMs:5000,shortlistCandleReviewMs:5000,newsRefreshMs:60000,automationCheckMs:5000,scanCandleMs:300000,growwIndexPollMs:1000,growwOptionPollMs:30000,positionMonitorMs:1000,positionEventsMs:3000,dataRetryMs:30000,historyCount:51,strategyWarmupBars:100,historyCacheBucketMs:300000,normalizedReadSpacingMs:250,instrumentCacheMs:21600000,universeRefreshAgeMs:604800000};
  const activityFields=['status','processing','phase','serverTime','phaseStartedAt','lastCheckedAt','lastScanStartedAt','lastSelectionAt','lastDecisionAt','lastScanSummary','lastNewsAt','lastNewsSummary','completedHistories','totalHistories','currentSymbol','nextScanEligibleAt','retryAt','selectedSymbol'];
  const cleanText=(v,max=1000)=>typeof v==='string'?v.slice(0,max):'';
  const reasons=v=>Array.isArray(v)?v.slice(0,5).filter(x=>typeof x==='string').map(x=>x.slice(0,500)):[];
  function snapshot(value,connection={state:'disconnected'}){
    // Copy an explicit display-only subset. Tokens, account data, input candles,
    // full requests, private URLs and credentials never cross to the flow window.
    const a=value?.automation||{},activity={};
    for(const key of activityFields)if(['string','boolean','number'].includes(typeof a.activity?.[key]))activity[key]=typeof a.activity[key]==='string'?a.activity[key].slice(0,2000):a.activity[key];
    activity.recentStages=(Array.isArray(a.activity?.recentStages)?a.activity.recentStages:[]).slice(-12).filter(s=>s&&typeof s==='object').map(s=>({phase:cleanText(s.phase,60),at:cleanText(s.at,60)}));
    const flow={};for(const key of Object.keys(defaults))if(Number.isFinite(a.flow?.[key])&&a.flow[key]>0)flow[key]=a.flow[key];
    for(const key of ['automationSchedulerEnabled','growwEnabled','aiEnabled','positionMonitorEnabled'])if(typeof a.flow?.[key]==='boolean')flow[key]=a.flow[key];
    for(const key of ['nifty50Workers','otherStockWorkers','otherCandidateBatchLimit','otherPriceScreenMs'])if(Number.isFinite(a.flow?.[key])&&a.flow[key]>0)flow[key]=a.flow[key];
    if(a.flow?.configurationSource==='server')flow.configurationSource='server';
    const selected=a.selection?.selected;
    return {automation:value?.automation?{enabled:a.enabled===true,busy:a.busy===true,mode:a.mode==='REAL'?'REAL':'PAPER',state:cleanText(a.state,60),message:cleanText(a.message,2000),scheduledEventRisk:a.scheduledEventRisk===true,activity,flow,
      newsPlan:a.newsPlan?{status:cleanText(a.newsPlan.status,60),lookbackUsed:cleanText(a.newsPlan.lookbackUsed,30),reasons:reasons(a.newsPlan.reasons),candidates:(Array.isArray(a.newsPlan.candidates)?a.newsPlan.candidates:[]).slice(0,5).map(n=>({symbol:cleanText(n.symbol,32),newsBias:cleanText(n.newsBias,20)}))}:null,
      selection:{status:cleanText(a.selection?.status,100),selected:selected?{symbol:cleanText(selected.symbol,32),score:Number.isFinite(selected.score)?selected.score:null}:null,reasons:reasons(a.selection?.reasons)},
      decision:a.decision?{action:cleanText(a.decision.action,50),symbol:cleanText(a.decision.symbol,32),reasons:reasons(a.decision.reasons)}:null}:null,
      connection:{state:['connected','disconnected','connecting','error'].includes(connection.state)?connection.state:'disconnected',message:cleanText(connection.message,500),updatedAt:cleanText(connection.updatedAt,60)}};
  }
  function duration(ms){const amount=ms<1000?ms:ms<60000?ms/1000:ms<3600000?ms/60000:ms<86400000?ms/3600000:ms/86400000;const unit=ms<1000?'ms':ms<60000?'second':ms<3600000?'minute':ms<86400000?'hour':'day';return amount+' '+unit+(unit!=='ms'&&amount!==1?'s':'');}
  function model(data,now=Date.now()){
    const safe=snapshot(data,data?.connection),a=safe.automation||{},f={...defaults,...a.flow},connection={...safe.connection};
    if(connection.state==='connected'&&(!Number.isFinite(Date.parse(connection.updatedAt))||now-Date.parse(connection.updatedAt)>15000)){
      connection.state='error';connection.message='No recent update from the source workspace. Keep it open and connected.';
    }
    const status=activityApi.model(safe.automation,connection),active=status.processing,phase=status.phase;
    const hasResult=Boolean(a.activity?.lastSelectionAt||a.activity?.lastDecisionAt);
    const gateBlocked=status.kind==='BLOCKED'||['STOPPED','PAUSED'].includes(status.kind);
    const validConnection=connection.state==='connected';
    const node=(key,title,caption,work,badge,detail)=>({key,title,caption,active:validConnection&&active&&work,badge:!validConnection?'Status unavailable':active&&work?'Processing':badge,detail});
    const nodes=[
      node('news','Trading AI news research','1 · Python AI → news shortlist',phase==='NEWS_ANALYSIS',a.newsPlan?.lookbackUsed==='LAST_7_DAYS'?'Previous seven days':a.newsPlan?.status==='READY'?"Today's news":'Waiting for news',
        'Check today’s available news in IST first. If none is available, use the previous seven days. Return up to three source-backed candidates; candles must still confirm an entry.'),
      node('groww','Groww market data','2 · Trader backend → Groww',['VERIFYING_DATA','INTRADAY_CHECK'].includes(phase),gateBlocked?'Entry pipeline waiting':'Completed candles',
        'Fetch the news candidates plus full NIFTY-50 context through the Java backend. Validate completed 5-minute histories; send all available candles in the configured 14-day window. Refresh the shortlist about every five seconds; reuse five-minute context caches.'),
      node('ai','Trading AI','3 · Trader backend → Python AI',['ANALYZING','FINAL_DECISION'].includes(phase),hasResult?'Last response available':'Waiting for valid data',
        'Confirm or reject the news shortlist using market, industry-basket, candle, volume and capital conditions. Require a successful Groww MIS preflight for the chosen stock and direction. Select at most one candidate, then request a separate final decision with its fresh quote.'),
      node('results','Analysis and results','4 · Java API → Your screens',false,hasResult?'Last result available':'Waiting for analysis',
        'Show candidate rankings, reasons and the final response in AI Analysis. Overview and Positions display simulated fills and P&L. Reports lists saved research analyses and replays.')
    ];
    const newerSelection=Date.parse(a.activity?.lastSelectionAt)>Date.parse(a.activity?.lastDecisionAt||'1970-01-01');
    const decision=newerSelection?null:a.decision;
    const selected=a.selection?.selected?.symbol||a.activity?.selectedSymbol||null;
    const resultTitle=decision?decision.action+' · '+decision.symbol:selected?'Last candidate: '+selected:a.activity?.lastSelectionAt?'No qualifying stock':'No analysis response received yet';
    const resultReasons=decision?.reasons?.length?decision.reasons:a.selection?.reasons?.length?a.selection.reasons:a.activity?.lastScanSummary?[a.activity.lastScanSummary]:[];
    const timing=(label,cadence,condition)=>({label,cadence,condition});
    const timings=[
      timing('Trading AI news collection','Cycle delay: '+duration(f.newsRefreshMs),'A separate bounded news worker reads permitted feeds. Use today’s news first, then the previous seven days only when today has no available news.'),
      timing('Automation rule checks',f.automationSchedulerEnabled===false?'Scheduler disabled':'Every '+duration(f.automationCheckMs),'Only when auto mode is on, the scheduler is enabled, no worker is busy, and retry backoff permits.'),
      timing('Full stock comparison','About '+duration(f.scanIntervalMs),'Send all available shortlist candles to AI. New completed candles arrive every '+duration(f.scanCandleMs)+'. One worker prevents overlapping cycles; failures back off.'),
      timing('Groww candle histories','Shortlist about every '+duration(f.shortlistCandleReviewMs),'Up to three stock histories refresh frequently; NIFTY and 50-stock context use '+duration(f.historyCacheBucketMs)+' caches. All valid returned completed bars are sent; network delays and throttling can extend the interval.'),
      timing('Groww intraday / MIS preflight','Up to '+f.intradayProbeLimit+' new checks per cycle','One-share read-only margin estimates before final selection; results cache for at most '+duration(f.intradayCheckCacheMs)+'. No order or funds reservation. Exact quantity and funds are checked again before a real entry.'),
      timing('AI final decision',f.aiEnabled===false?'Trading AI disabled':'Only after a candidate is selected','Uses a fresh quote. At most one final-decision attempt per five-second cycle; independent paper risk approval is still required.'),
      timing('Groww index LTP feed',f.growwEnabled===false?'Groww disabled':'Cycle delay: '+duration(f.growwIndexPollMs),'Background refresh for NIFTY/BANKNIFTY, if Groww is enabled and credentials are configured. Not a full stock analysis.'),
      timing('Groww option-chain context',f.growwEnabled===false?'Groww disabled':'Cycle delay: '+duration(f.growwOptionPollMs),'Separate background context with expiry lookup; not the cash-stock selector and not an options execution strategy.'),
      timing('Open paper-position monitoring',f.positionMonitorEnabled===false?'Monitoring disabled':'Cycle delay: '+duration(f.positionMonitorMs),'Only one active position. Hold until its target, stop, configured daily limit, manual/emergency exit, or 14:59 IST. New AI signals do not switch the held stock.'),
      timing('Position events sent to AI','Queue check: '+duration(f.positionEventsMs),'Fresh position snapshots are queued about every five seconds. At most one periodic update per position waits in the queue; lifecycle events are retained and failures use backoff.'),
      timing('Browser automation status','Every 5 seconds','GET /api/v1/auto/status reads state and P&L. The separate flow window shares this feed; it does not create another broker or AI polling loop.'),
      timing('Overview quote display','Every 1 second while visible','Reads the Java cached market overview; this screen request does not itself fetch a new Groww quote.'),
      timing('Trading AI health display','Every 15 seconds while visible','Health/readiness check, not market analysis.'),
      timing('Data/service failure retry','Not before '+duration(f.dataRetryMs),'Automation and background market-data collection wait after failure. Session, risk, local read budgets and service limits still apply.')
    ];
    return {status,nodes,timings,configurationSource:a.flow?.configurationSource==='server'?'Configured server timings':'Default code timings · connect to load server settings',
      gateActive:active&&phase==='CHECKING_RULES',gateBlocked,validConnection,resultTitle,resultReasons,
      resultAt:activityApi.time(decision?a.activity?.lastDecisionAt:a.activity?.lastSelectionAt),
      monitoring:status.kind==='MONITORING',riskActive:active&&phase==='RISK_CHECK',f,
      observed:{checked:status.checkedAt,comparison:status.scanAt,decision:status.decisionAt,received:status.receivedAt}};
  }
  function render(target,data,now=Date.now()){
    if(!target)return;
    const v=model(data,now),doc=target.ownerDocument||document;
    const add=(parent,tag,value,cls)=>{const el=doc.createElement(tag);if(cls)el.className=cls;if(value!==undefined)el.textContent=String(value);parent.append(el);return el;};
    // Keep the API-details disclosure open across status updates.
    const detailsOpen=target.querySelector?.('details')?.open||false;
    target.replaceChildren();target.className='system-flow-view';
    const summary=add(target,'div',undefined,'flow-current');summary.dataset.kind=v.status.kind.toLowerCase();
    add(summary,'strong',v.status.title);add(summary,'span',v.status.kind==='PROCESSING'?'Processing':v.status.kind==='BLOCKED'?'Blocked':v.status.kind==='UNKNOWN'?'Update unavailable':v.status.mode,'flow-badge');
    add(target,'p',v.status.message,'flow-subtitle');
    const gate=add(target,'div',undefined,'flow-gate'+(v.gateBlocked?' blocked':v.gateActive?' active':''));
    add(gate,'strong','Entry gate · session and configured rules');add(gate,'p',v.gateBlocked?'The entry pipeline is waiting here. Background index feeds and existing-position monitoring can continue independently.':'Every eligible cycle checks the calendar, event restriction, kill switch, existing positions and daily limits before fetching stock histories.');
    const lane=add(target,'ol',undefined,'flow-lane');lane.setAttribute('aria-label','News to Groww to AI to results');
    for(const n of v.nodes){const card=add(lane,'li',undefined,'flow-node'+(n.active?' active':''));card.dataset.flowNode=n.key;add(card,'p',n.caption,'flow-caption');add(card,'h3',n.title);add(card,'span',n.badge,'flow-badge');add(card,'p',n.detail);}
    const lower=add(target,'div',undefined,'flow-lower');
    const risk=add(lower,'section',undefined,'flow-risk'+(v.riskActive?' active':''));add(risk,'h3','Final risk → paper engine → monitoring');
    add(risk,'p',v.monitoring?'An open paper position is being monitored. Fresh quotes update P&L and check exit conditions.':'Keep one active position and hold it until target, stop or configured limits. At 14:59 IST request exits for all managed positions. A candidate does not authorize an order. Java reloads the decision, checks a fresh quote and configured limits, then records a simulated fill only if every check passes.');
    add(risk,'p','Execution uses the workspace-selected mode. This read-only window cannot place or close orders.','flow-small');
    const result=add(lower,'section',undefined,'flow-result');add(result,'h3',v.resultTitle);add(result,'p','Last response time: '+v.resultAt,'flow-small');
    for(const reason of v.resultReasons)add(result,'p',reason);
    const links=add(result,'div',undefined,'flow-links');for(const [screen,label]of [['analysis','AI Analysis'],['reports','Reports'],['positions','Positions']]){const b=add(links,'button',label);b.type='button';b.dataset.flowScreen=screen;}
    const facts=add(target,'div',undefined,'flow-facts');for(const [label,value]of [['Last server check',v.observed.checked],['Last stock comparison',v.observed.comparison],['Last final decision',v.observed.decision],['Status received',v.observed.received]]){const row=add(facts,'div');add(row,'span',label);add(row,'strong',value);}
    if(data?.automation?.newsPlan){const n=data.automation.newsPlan;add(target,'p','News window: '+(n.lookbackUsed==='LAST_7_DAYS'?'Previous seven days (no today stock news available)':'Today (IST)')+' · Shortlist: '+((n.candidates||[]).map(x=>x.symbol).join(', ')||'None yet'),'flow-small');}
    add(target,'h3','How frequently calls happen');add(target,'p',v.configurationSource+'. Scheduled delays are not completion guarantees: latency, caching, credentials, disabled services and throttling can reduce the actual call rate.','flow-small');
    const wrap=add(target,'div',undefined,'flow-table');const table=add(wrap,'table');const tr=add(add(table,'thead'),'tr');for(const name of ['Activity','Frequency / trigger','When it runs'])add(tr,'th',name);
    const tbody=add(table,'tbody');for(const t of v.timings){const row=add(tbody,'tr');add(row,'td',t.label);add(row,'td',t.cadence);add(row,'td',t.condition);}
    const details=add(target,'details',undefined,'flow-details');details.open=detailsOpen;add(details,'summary','API paths, caches and what the highlights mean');
    const entries=[['Java → Trading AI','POST /api/v1/automation/news-plan · today-first news shortlist'],['Java → Groww','GET /v1/historical/candles · completed OHLCV'],['Java → Trading AI','POST /api/v1/automation/select · candidate ranking and reasons'],['Java → Groww','GET /v1/live-data/quote · selected candidate and independent risk recheck'],['Java → Trading AI','POST /api/v1/decisions/evaluate · final action and reasons'],['Paper engine → Trading AI','POST /api/v1/positions/events · pending position events'],['Browser → Java','GET /api/v1/auto/status · current state, selection, decision and paper portfolio']];
    for(const [label,value]of entries){const p=add(details,'p');add(p,'strong',label+': ');add(p,'code',value);}
    add(details,'p','The normalised history/quote gateway spaces its reads by at least '+duration(v.f.normalizedReadSpacingMs)+', with a separate shared broker read budget. Instrument metadata is cached for '+duration(v.f.instrumentCacheMs)+'. The stock-universe snapshot is refreshed on a scan when at least '+duration(v.f.universeRefreshAgeMs)+' old.');
    add(details,'p','The automatic strategy requires '+v.f.strategyWarmupBars+' completed 5-minute bars per history, including earlier sessions. A highlight reports the latest server stage; it is not a per-request network trace. Short operations may finish between screen refreshes. Automatic selection/decision records appear in AI Analysis; saved manual research reports and replays appear in Reports.');
    return v;
  }
  function createParentBridge(win,onNavigate,onWindowState){
    let child=null,current=snapshot(null),listener;
    const isOpen=()=>{try{return Boolean(child&&!child.closed&&child.location.origin===win.location.origin);}catch{return false;}};
    const send=()=>{if(isOpen())child.postMessage({type:'TRADING_FLOW_UPDATE',data:current},win.location.origin);};
    listener=event=>{
      if(event.origin!==win.location.origin||event.source!==child)return;
      if(event.data?.type==='TRADING_FLOW_READY')send();
      if(event.data?.type==='TRADING_FLOW_ACK')onWindowState?.({state:'connected'});
      if(event.data?.type==='TRADING_FLOW_NAVIGATE'&&['analysis','reports','positions'].includes(event.data.screen))onNavigate?.(event.data.screen);
    };
    win.addEventListener('message',listener);
    return {isOpen,publish(value,connection){current=snapshot(value,connection);send();},open(){if(isOpen()){child.focus();send();return true;}child=win.open('/system-flow.html','trading-flow-'+Date.now(),'popup,width=1280,height=880,scrollbars=yes,resizable=yes');return Boolean(child);},dispose(){win.removeEventListener('message',listener);}};
  }
  const api={snapshot,model,render,createParentBridge,duration};
  if(typeof module==='object'&&module.exports){module.exports=api;return;}
  const $=id=>document.getElementById(id);
  if(document.body.hasAttribute('data-system-flow')){
    let latest=snapshot(null);const origin=root.location.origin;
    const repaint=()=>render($('system-flow-content'),latest);
    const request=()=>{try{if(root.opener&&!root.opener.closed)root.opener.postMessage({type:'TRADING_FLOW_READY'},origin);}catch{}};
    root.addEventListener('message',event=>{if(event.origin!==origin||event.source!==root.opener||event.data?.type!=='TRADING_FLOW_UPDATE')return;latest=snapshot(event.data.data,event.data.data?.connection);repaint();root.opener.postMessage({type:'TRADING_FLOW_ACK'},origin);});
    $('system-flow-content').addEventListener('click',event=>{const button=event.target.closest('[data-flow-screen]');if(button&&root.opener&&!root.opener.closed)root.opener.postMessage({type:'TRADING_FLOW_NAVIGATE',screen:button.dataset.flowScreen},origin);});
    $('system-flow-refresh').onclick=request;repaint();request();setInterval(repaint,5000);return;
  }
  if(!document.body.hasAttribute('data-workspace'))return;
  let latest=null,connection={state:'disconnected'};
  const dialog=$('system-flow-dialog');
  const navigate=screen=>{dialog.close();root.WorkspaceShell?.navigate(screen);root.focus();};
  const bridge=createParentBridge(root,navigate,()=>{$('system-flow-window-note').textContent='Separate window connected · sharing the workspace status feed.';});
  const repaint=()=>{if(dialog.open)render($('system-flow-content'),snapshot(latest,connection));};
  for(const button of document.querySelectorAll('[data-open-system-flow]'))button.addEventListener('click',()=>{dialog.showModal();repaint();});
  $('system-flow-close').onclick=()=>dialog.close();
  $('system-flow-popout').onclick=()=>{if(bridge.open())dialog.close();else $('system-flow-window-note').textContent='The separate window could not open. This panel will continue to update.';};
  $('system-flow-content').addEventListener('click',event=>{const button=event.target.closest('[data-flow-screen]');if(button)navigate(button.dataset.flowScreen);});
  root.SystemFlowWindow={publish(value,state){latest=value;connection=state;bridge.publish(value,state);repaint();},isOpen:bridge.isOpen};
})(globalThis);
