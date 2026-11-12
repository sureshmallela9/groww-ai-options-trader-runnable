(function(root){
  'use strict';
  const names={INTRADAY_CHECK:'Checking Groww intraday / MIS eligibility',NEWS_ANALYSIS:"Researching market news",NEWS_WAIT:"Waiting for news evidence",CHECKING_RULES:'Checking session and rules',STARTING:'Starting checks',VERIFYING_DATA:'Validating market data',ANALYZING:'Analyzing stocks',FINAL_DECISION:'Checking the final decision',RISK_CHECK:'Applying entry rules',NO_TRADE:'No qualifying stock',EVENT_RISK:'Scheduled event restriction',BLOCKED:'Entries blocked',DAILY_LIMIT:'Daily limit reached',DATA_UNAVAILABLE:'Waiting for required data',CALENDAR_MISSING:'Session calendar missing',MARKET_CLOSED:'Market closed',ENTRY_WINDOW_CLOSED:'Entry window closed',BEFORE_OPEN:'Waiting for market open',NON_TRADING_DAY:'Non-trading day',WAITING_FOR_SESSION:'Waiting for a verified session',MONITORING_POSITION:'Monitoring a position',POSITION_OPEN:'Position open',EXIT_PENDING:'Exit pending',REJECTED:'Entry rejected',STOPPED:'Automation stopped',PAUSED:'Automation paused'};
  const blocked=new Set(["NEWS_WAIT",'CALENDAR_NOT_REVIEWED','SPECIAL_SESSION_NOT_CONFIGURED','DATE_NOT_CONFIGURED','INVALID_SESSION_TIMES','CALENDAR_INVALID','EVENT_RISK','BLOCKED','DAILY_LIMIT','DATA_UNAVAILABLE','CALENDAR_MISSING','MARKET_CLOSED','ENTRY_WINDOW_CLOSED','BEFORE_OPEN','NON_TRADING_DAY','WAITING_FOR_SESSION','REJECTED']);
  function readable(value){
    if(Array.isArray(value))return value.join(' · ');
    try{const parsed=JSON.parse(value);if(Array.isArray(parsed))return parsed.join(' · ');}catch{}
    return typeof value==='string'?value:'';
  }
  function time(value){const n=Date.parse(value);return Number.isFinite(n)?new Date(n).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:true})+' IST':'Not yet';}
  function model(automation,connection={state:'connected'}){
    const a=automation||{},activity=a.activity||{},phase=activity.phase||a.state||'STOPPED';
    const connected=connection.state==='connected'&&Boolean(automation);
    let kind=activity.status||(a.enabled?(blocked.has(phase)?'BLOCKED':a.busy?'PROCESSING':phase.includes('POSITION')?'MONITORING':'WAITING'):phase==='STOPPED'?'STOPPED':'PAUSED');
    if(!connected)kind=connection.state==='connecting'?'CONNECTING':connection.state==='error'?'UNKNOWN':'DISCONNECTED';
    const processing=connected&&a.enabled===true&&kind==='PROCESSING'&&a.busy===true&&(activity.processing??true);
    if(kind==='PROCESSING'&&!processing)kind='WAITING';
    const titles={PROCESSING:names[phase]||'Processing analysis',BLOCKED:names[phase]||'Automation blocked',WAITING:phase==='NO_TRADE'?'Scan complete · no qualifying stock':'Waiting for next check',MONITORING:names[phase]||'Monitoring a position',STOPPED:'Automation stopped',PAUSED:'Automation paused',CONNECTING:'Connecting to automation',UNKNOWN:'Status update unavailable',DISCONNECTED:'Connect to view activity'};
    let message=readable(a.message)||'The next server check will report the current activity.';
    if(processing){
      message={NEWS_ANALYSIS:'Trading AI is checking today’s news, or the previous seven days if none is available today, before candle confirmation.',CHECKING_RULES:'Checking the session, restrictions and configured limits.',VERIFYING_DATA:'Loading and validating completed histories before stock comparison.',ANALYZING:'Comparing the maintained stocks and their industry baskets. No final stock yet.',FINAL_DECISION:'Rechecking the selected candidate with a fresh quote and final analysis.',RISK_CHECK:'Rechecking configured rules before any simulated entry.',STARTING:'Preparing server-side paper checks.'}[phase]||message;
    }
    if(a.enabled&&a.scheduledEventRisk&&connected){kind='BLOCKED';message='The manual scheduled-event pause is on. Automatic stock analysis and new entries are blocked. Review the manual pause in Automation to resume normal checks.';}
    if(!connected)message=connection.state==='error'?'The latest status could not be received. Previous results may be stale. '+(connection.message||''):connection.state==='connecting'?'Waiting for the first successful status response.':'Connect the workspace to see live processing status.';
    const actuallyProcessing=processing&&kind==='PROCESSING';
    const completed=Number(activity.completedHistories),total=Number(activity.totalHistories);
    const progress=actuallyProcessing&&phase==='VERIFYING_DATA'&&Number.isInteger(completed)&&Number.isInteger(total)&&completed>=0&&total>0&&completed<=total?{completed,total,symbol:activity.currentSymbol||null}:null;
    const elapsed=actuallyProcessing?Math.max(0,Math.floor((Date.parse(activity.serverTime)-Date.parse(activity.phaseStartedAt))/1000)):null;
    const recent=connected&&Array.isArray(activity.recentStages)?activity.recentStages.slice(-4).reverse().map(stage=>({label:names[stage.phase]||String(stage.phase||'').replaceAll('_',' '),at:time(stage.at)})):[];
    return {universeWorkers:connected?activity.universeWorkers:null,kind,processing:actuallyProcessing,title:a.enabled&&a.scheduledEventRisk&&connected?'Manual event pause':titles[kind]||'Waiting for status',message,phase,
      mode:connected?(a.enabled?'Auto on · ':'Auto off · ')+(a.mode==='REAL'?'Real':'Paper'):'Paper',progress,elapsed:Number.isFinite(elapsed)?elapsed:null,
      checkedAt:time(activity.lastCheckedAt||a.lastCheckedAt),receivedAt:connected?time(connection.updatedAt):'Not connected',
      scanAt:time(activity.lastSelectionAt),decisionAt:time(activity.lastDecisionAt),lastResult:readable(activity.lastScanSummary),
      next:connected&&activity.nextAction?activity.nextAction:connected&&activity.retryAt?'Retry not before '+time(activity.retryAt):connected&&activity.nextScanEligibleAt?'Next candle eligible at '+time(activity.nextScanEligibleAt):'',
      recent,selectedSymbol:activity.selectedSymbol||null};
  }
  function render(target,automation,{compact=false,connection={state:'connected'}}={}){
    if(!target)return;
    const v=model(automation,connection),doc=target.ownerDocument||document;
    const add=(parent,tag,value,className)=>{const el=doc.createElement(tag);if(className)el.className=className;if(value!==undefined)el.textContent=String(value);parent.append(el);return el;};
    target.replaceChildren();target.className='automation-activity'+(compact?' compact':'');target.setAttribute('data-activity',v.kind.toLowerCase());
    const heading=add(target,'div',undefined,'activity-heading');
    const title=add(heading,'div',undefined,'activity-title');add(title,'span','',v.processing?'activity-indicator processing':'activity-indicator');
    const label=add(title,'strong',v.title);label.setAttribute('role','status');
    add(heading,'span',v.kind==='PROCESSING'?'Processing':v.kind==='BLOCKED'?'Blocked':v.kind==='WAITING'?'Waiting':v.mode,'activity-badge');
    add(target,'p',v.message,'activity-message');
    if(v.progress){
      add(target,'p',v.progress.completed+' / '+v.progress.total+' histories validated across the requested stock groups'+(v.progress.symbol?' · Fetching '+v.progress.symbol:''),'activity-progress-text');
      const meter=add(target,'progress');meter.max=v.progress.total;meter.value=v.progress.completed;meter.setAttribute('aria-label','Validated histories');
    }
    if(v.universeWorkers&&!compact){
      const groups=add(target,'div',undefined,'activity-facts');
      for(const [key,label]of [['nifty50','Worker 1 · NIFTY 50'],['otherStocks','Worker 2 · Other stocks']]){
        const lane=v.universeWorkers[key]||{};
        add(groups,'p',label+' · '+String(lane.status||'IDLE').replaceAll('_',' ')+(Number.isFinite(lane.totalHistories)?' · '+lane.completedHistories+' / '+lane.totalHistories+' histories':'')+(lane.currentSymbol?' · '+lane.currentSymbol:''),'hint');
      }
    }
    if(v.elapsed!==null)add(target,'p','Current step: '+v.elapsed+' seconds'+(v.elapsed>=30?' · Still processing; completion time depends on the data and AI services.':''),'hint');
    if(v.next)add(target,'p',v.next+'. Subject to session, data and risk checks.','activity-next');
    const facts=add(target,'dl',undefined,'activity-facts');
    const fact=(name,value)=>{const row=add(facts,'div');add(row,'dt',name);add(row,'dd',value);};
    fact('Last server check',v.checkedAt);fact('Status received',v.receivedAt);
    if(!compact){fact('Last stock comparison',v.scanAt);fact('Last final decision',v.decisionAt);}
    if(v.lastResult)add(target,'p','Last scan: '+v.lastResult,'activity-result');
    if(!compact&&v.recent.length){const list=add(target,'ol',undefined,'activity-history');list.setAttribute('aria-label','Recent automation stages');for(const stage of v.recent){const row=add(list,'li');add(row,'span',stage.label);add(row,'time',stage.at);}}
    if(connection.state==='connected')add(target,'p','Status refreshes every 5 seconds while this page is visible. AI receives full available shortlist candles about every 5 seconds; new five-minute candles join after completion.','hint');
    return v;
  }
  const api={model,render,time};if(typeof module==='object'&&module.exports)module.exports=api;else root.AutomationActivity=api;
})(globalThis);
