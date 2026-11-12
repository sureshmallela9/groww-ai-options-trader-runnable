(function(root){
  'use strict';
  function chartModel(bars,markers,width=900,height=260,annotations=[]){
    const points=bars.map(b=>({time:Date.parse(b.timestamp),price:Number(b.close)}))
      .filter(p=>Number.isFinite(p.time)&&Number.isFinite(p.price)&&p.price>0).sort((a,b)=>a.time-b.time);
    const fills=markers.filter(m=>['BUY','SELL'].includes(m.side)).map(m=>({...m,time:Date.parse(m.time),price:Number(m.price)}))
      .filter(p=>Number.isFinite(p.time)&&Number.isFinite(p.price)&&p.price>0);
    const pending=annotations.map(p=>({...p,time:Date.parse(p.requestedAt)})).filter(p=>Number.isFinite(p.time));
    const all=points.concat(fills);if(!all.length)return {points:[],markers:[],pending:[]};
    const from=Math.min(...all.map(p=>p.time),...pending.map(p=>p.time)),to=Math.max(...all.map(p=>p.time),...pending.map(p=>p.time));
    const low=Math.min(...all.map(p=>p.price)),high=Math.max(...all.map(p=>p.price));
    const range=Math.max(high-low,high*.002,0.01);
    const place=p=>({...p,x:65+(p.time-from)/Math.max(1,to-from)*(width-90),
      y:20+(high+range*.1-p.price)/(range*1.2)*(height-55)});
    return {points:points.map(place),markers:fills.map(place),pending:pending.map(p=>({...p,x:65+(p.time-from)/Math.max(1,to-from)*(width-90)})),low,high,from,to};
  }
  if(typeof module==='object'&&module.exports){module.exports={chartModel};return;}
  const host=document.getElementById('automatic-paper');if(!host)return;
  const workspaceLayout=host.getAttribute?.('data-layout')==='workspace';
  host.innerHTML=`<div class="auto-heading"><div><h2>Automatic trading</h2><p>Validate → analyze NIFTY 50 → select one stock → recheck risk → submit a managed entry. Full available shortlist candle analysis about every five seconds; only completed five-minute candles are used.</p></div><span class="auto-badge">PAPER · live execution disabled</span></div>
  <div class="auto-connection"><label>Connection token <input id="auto-token" type="password" autocomplete="off" placeholder="Shared INTERNAL_API_TOKEN"></label><button id="auto-connect">Connect</button></div>
  <div class="auto-controls"><label>Execution mode <select id="auto-mode"><option value="PAPER">Paper simulation</option><option value="REAL">Real orders</option></select></label>
  <label><input id="auto-event" type="checkbox" aria-describedby="auto-event-help"> Pause new entries for a scheduled event (manual)</label>
  <p id="auto-event-help" class="hint" role="status">Manual pause: switch this on when you want to stop automatic stock analysis and new entries for a scheduled event.</p>
  <label><input id="auto-confirm" type="checkbox"> Allow automatic simulated paper entries under the configured rules</label>
  <button id="auto-start">Start / apply paper auto mode</button><button id="auto-stop">Stop new entries</button><button id="auto-exit" class="auto-danger">Stop and exit paper positions</button></div>
  <p id="auto-status" role="status">Connect to load managed positions and P&amp;L.</p><p id="auto-rules" class="auto-muted"></p>
  <div class="auto-metrics"><div>Today's realized P&amp;L<strong id="auto-realized">—</strong></div><div>Open P&amp;L<strong id="auto-unrealized">—</strong></div><div>Today's net P&amp;L<strong id="auto-net">—</strong></div></div>
  <div class="auto-chart-heading"><h3>Stock, fills and pending exits</h3><label>Stock <select id="auto-chart-symbol"><option value="">Waiting for selection</option></select></label><span><b class="auto-buy">▲ BUY fill</b> &nbsp; <b class="auto-sell">▼ SELL fill</b> &nbsp; <b class="auto-pending">♦ EXIT PENDING · no fill</b></span></div>
  <svg id="auto-chart" viewBox="0 0 900 260" role="img" aria-label="Stock history with blue BUY up-triangles, purple SELL down-triangles and amber pending-exit flags"></svg><p id="auto-chart-note" class="auto-muted">Recorded fills appear only after a broker-confirmed real trade or a completed paper simulation.</p>
  <div class="auto-table"><table><thead><tr><th>Stock / position</th><th>Quantity</th><th>Entry</th><th>Exit / latest</th><th>Net P&amp;L</th><th>Action</th></tr></thead><tbody id="auto-positions"></tbody></table></div>
  <details><summary>Selection, decision and rule evidence</summary><pre id="auto-evidence">No automatic selection yet.</pre></details>
  <p class="auto-muted">Runs on the server until stopped; a restart pauses automatic entries. Existing positions remain under the risk monitor. P&amp;L includes configured estimated costs. Industry confirmation uses a labelled derived basket of maintained constituents, not an exchange sector index. <a href="/paper-workspace.html">Detailed paper workspace</a></p>`;
  const style=document.createElement('style');style.textContent=`
  #automatic-paper{padding:22px;background:#111e2c;border:1px solid #33475e;border-radius:16px;margin:18px 0;color:#eef4ff}#automatic-paper h2,#automatic-paper h3{margin:0 0 8px}#automatic-paper p{line-height:1.5}.auto-heading,.auto-chart-heading,.auto-controls,.auto-connection{display:flex;gap:14px;align-items:center;flex-wrap:wrap}.auto-heading{justify-content:space-between}.auto-heading p{max-width:760px;color:#aabbcf}.auto-controls{margin:18px 0}.auto-controls label{display:flex;align-items:center;gap:8px;font-weight:400}.auto-controls input[type=checkbox]{width:auto;margin:0}.auto-connection input,.auto-controls select,#auto-chart-symbol{background:#0a1522;border:1px solid #45607a;border-radius:7px;color:white;padding:9px}.auto-connection input{width:250px;max-width:100%;margin-left:8px}#automatic-paper button{padding:10px 13px;background:#254c45;border:1px solid #427568;border-radius:8px;color:#f1fffa;cursor:pointer}#automatic-paper button:disabled{opacity:.5;cursor:wait}.auto-danger{background:#5b2634!important}.auto-badge{color:#7ee6c0;font-size:12px}.auto-muted{color:#aabbcf;font-size:12px}.auto-metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin:18px 0}.auto-metrics>div{border:1px solid #30465d;border-radius:10px;padding:14px;color:#aabbcf}.auto-metrics strong{display:block;font-size:24px;color:#edf5ff;margin-top:5px}.auto-profit{color:#38d9a9!important}.auto-loss{color:#ff7185!important}.auto-buy{color:#50a7ff!important}.auto-sell{color:#c59bff!important}.auto-pending{color:#f6c56f!important}.auto-table{overflow:auto}#automatic-paper table{width:100%;border-collapse:collapse}#automatic-paper td,#automatic-paper th{padding:10px;text-align:left;border-bottom:1px solid #2d435b;white-space:nowrap}#auto-chart{width:100%;height:auto;min-height:180px;background:#091421;border-radius:10px;margin-top:10px}#automatic-paper pre{white-space:pre-wrap;max-height:450px;overflow:auto;font-size:12px}#auto-pnl-bar{position:fixed;left:0;right:0;bottom:0;z-index:40;background:#102332;border-top:1px solid #42617a;padding:12px 20px;display:flex;gap:18px;justify-content:center;flex-wrap:wrap;color:#edf5ff;font:14px system-ui}body{padding-bottom:90px!important}@media(max-width:650px){.auto-metrics{grid-template-columns:1fr}.auto-metrics strong{font-size:22px}.auto-connection input{margin-left:0;width:100%}#automatic-paper{padding:15px}#auto-pnl-bar{font-size:12px;padding:8px;gap:9px}}
  `;if(!workspaceLayout)document.head.append(style);
  if(workspaceLayout){
    const move=(selector,target)=>{const parent=document.getElementById(target);for(const element of host.querySelectorAll(selector))parent.append(element);};
    move('.auto-connection','workspace-connection');move('.auto-controls','workspace-controls');
    move('#auto-status, #auto-rules','workspace-activity');move('.auto-metrics','workspace-metrics');
    move('.auto-chart-heading, #auto-chart, #auto-chart-note','workspace-chart');move('.auto-table','workspace-positions');
    move('details','workspace-evidence');move(':scope > p.auto-muted','workspace-footnote');
    const internalMode=document.getElementById('auto-mode').closest('label');internalMode.hidden=true;
    const actions=document.createElement('div');actions.className='auto-action-row';
    for(const id of ['auto-start','auto-stop','auto-exit'])actions.append(document.getElementById(id));
    document.getElementById('workspace-controls').querySelector('.auto-controls').append(actions);
    document.getElementById('auto-token').placeholder='Workspace access token';
    const metrics=document.querySelector('#workspace-metrics .auto-metrics');
    metrics.prepend(document.getElementById('auto-net').parentElement);
    const count=document.createElement('div');count.append(document.createTextNode('Open positions'));
    const number=document.createElement('strong');number.id='auto-open-count';number.textContent='—';count.append(number);
    document.querySelector('#workspace-metrics .auto-metrics').append(count);
    host.hidden=true;
  }
  const bar=document.createElement('div');bar.id='auto-pnl-bar';bar.setAttribute('aria-live','polite');bar.textContent='Connect to view mode and P&L';document.body.append(bar);
  const $=id=>document.getElementById(id);let token='',running=false,generation=0,selected='',lastPositions=[],eventDirty=false,previousSymbols='',lastFooter='',authError=false,lastValue=null;
  const subscribers=new Set(),connectionSubscribers=new Set();let positionPage=0;let connectionState={state:'disconnected'},activeRefresh=null;
  function connection(value){connectionState=value;for(const callback of connectionSubscribers)callback(value);}

  const money=v=>new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(Number(v||0));
  function text(parent,tag,value){const el=document.createElement(tag);el.textContent=value;parent.append(el);return el;}
  function pnl(el,value){el.textContent=money(value);el.classList.toggle('auto-profit',Number(value)>=0);el.classList.toggle('auto-loss',Number(value)<0);}
  async function api(path,body,method=null,adminToken=null){const requestGeneration=generation;
    if(root.WorkspaceAPI){try{return await root.WorkspaceAPI.platform(path,body,method,adminToken);}catch(error){if(error.status===401&&requestGeneration===generation)authError=true;throw error;}}
    const headers={'Content-Type':'application/json','Authorization':'Bearer '+token};if(adminToken)headers['X-Admin-Token']=adminToken;
    const response=await fetch('/api/v1'+path,{method:method||(body===undefined?'GET':'POST'),headers,body:body===undefined?undefined:JSON.stringify(body),signal:root.AbortSignal?.timeout?.(25000)});
    const value=await response.json();if(!response.ok){if(response.status===401&&requestGeneration===generation)authError=true;throw Object.assign(Error(value.error||value.detail||'Paper service unavailable.'),{status:response.status});}return value;
  }
  function messageText(value){try{const items=JSON.parse(value);if(Array.isArray(items))return items.join(' · ');}catch{}return value||'';}
  function showPosition(item){
    const modal=$('workspace-position-dialog'),content=$('workspace-position-content');
    if(!workspaceLayout||!modal?.showModal){$('auto-evidence').textContent=JSON.stringify(item,null,2);const details=$('auto-evidence').closest?.('details');if(details)details.open=true;return;}
    if(modal.open&&modal.dataset.positionId===item.intentId)return;modal.dataset.positionId=item.intentId;content.replaceChildren();text(content,'p','Record snapshot when opened. The footer continues to show the latest portfolio P&L.');text(content,'h3',item.symbol+' · '+(root.WorkspaceShell?.humanState(item.state)||String(item.state||'').replaceAll('_',' ')));
    const timeLabel=value=>Number.isFinite(Date.parse(value))?new Intl.DateTimeFormat('en-IN',{timeZone:'Asia/Kolkata',day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit'}).format(Date.parse(value))+' IST':'—';
    const rows=document.createElement('div');content.append(rows);
    for(const [label,value]of [['Position direction',item.positionSide||'LONG'],['Latest AI action',item.lastAiAction||'Waiting'],['AI update time',timeLabel(item.lastAiReceivedAt)],['Filled quantity',item.filledQuantity??'—'],['Entry',item.entryPrice==null?'—':money(item.entryPrice)],['Entry time',timeLabel(item.entryTime)],['Latest / exit',item.exitPrice==null?(item.lastPrice==null?'—':money(item.lastPrice)):money(item.exitPrice)],['Exit / quote time',timeLabel(item.exitTime||item.quoteTimestamp)],['Net P&L',item.filledQuantity>0?money(item.state==='POSITION_CLOSED'?item.realizedPnl:item.unrealizedPnl):'—'],['Stop-loss',item.stopLoss==null?'—':money(item.stopLoss)],['Target',item.targetPrice==null?'—':money(item.targetPrice)]]){const row=text(rows,'div','');row.className='rule-row';text(row,'span',label);text(row,'strong',String(value));}
    if(item.riskRejections?.length){text(content,'h3','Entry checks');for(const reason of item.riskRejections)text(content,'p',reason);}
    if(item.lastAiAssessment)text(content,'p','AI position review: '+item.lastAiAssessment);
    if(item.exitReason)text(content,'p','Exit reason: '+item.exitReason);
    if(item.incident||item.brokerIncident)text(content,'p','Data / execution issue: '+(item.brokerIncident||item.incident));
    if(item.mode==='REAL'){text(content,'p','Broker-checked entry margin '+money(item.marginRequired)+' · Cash reserve for costs '+money(item.marginCostReserve)+' · Exposure ceiling '+(item.maxIntradayLeverage??1)+'× · Margin checked '+timeLabel(item.marginCheckedAt));text(content,'p','Broker protection: '+(item.protectionStatus||'Awaiting confirmed entry'));if(item.lastBrokerReceivedAt)text(content,'p','Broker update: '+timeLabel(item.lastBrokerReceivedAt));if(item.executionError)text(content,'p',item.executionError);text(content,'p','Trade prices and quantities are broker-confirmed. Charges and net P&L remain estimates until broker-finalized.');}
    if(item.timeline?.length){text(content,'h3','Position timeline');const list=text(content,'ul','');for(const event of item.timeline)text(list,'li',String(event.state||'').replaceAll('_',' ')+' · '+(event.reason||''));}
    const raw=text(content,'details','');text(raw,'summary','Full technical record');text(raw,'pre',JSON.stringify(item,null,2));if(!modal.open)modal.showModal();
  }
  const overviewViews=new Map(),overviewInteraction={dragging:false};
  function draw(value){
    if(root.ShortlistCharts?.render){
      if(overviewInteraction.dragging)return;
      const old=$('auto-chart');old.hidden=true;old.style.display='none';$('auto-chart-note').hidden=true;
      let host=$('overview-candles');if(!host){host=document.createElement('div');host.id='overview-candles';old.parentNode.insertBefore(host,old);}
      root.ShortlistCharts.render(host,{stocks:[{symbol:value.symbol||$('auto-chart-symbol').value,bars:value.bars||[],markers:value.markers||[],marketDepth:value.marketDepth}],positions:lastPositions},{limit:60,views:overviewViews,interaction:overviewInteraction,compact:true});return;
    }
    const svg=$('auto-chart');svg.replaceChildren();const pending=root.PaperTradeMarkers?.pendingExits(lastPositions,value.symbol||$('auto-chart-symbol').value)||[];const model=chartModel(value.bars||[],value.markers||[],900,260,pending);
    const make=(name,attrs)=>{const e=document.createElementNS('http://www.w3.org/2000/svg',name);for(const [k,v]of Object.entries(attrs))e.setAttribute(k,v);svg.append(e);return e;};
    if(model.points.length)make('path',{d:model.points.map((p,i)=>(i?'L':'M')+p.x.toFixed(2)+','+p.y.toFixed(2)).join(' '),fill:'none',stroke:'#819dff','stroke-width':2.5});
    for(const p of model.pending){
      make('line',{x1:p.x,x2:p.x,y1:32,y2:225,stroke:'#f6c56f','stroke-dasharray':'5 4','stroke-width':1.5});
      const flag=make('path',{d:'M '+p.x+' 12 l 7 9 l -7 9 l -7 -9 Z',fill:'#091421',stroke:'#f6c56f','stroke-width':2});
      const title=document.createElementNS('http://www.w3.org/2000/svg','title');title.textContent='Exit pending · '+p.side+' '+p.quantity+' · No fill recorded';flag.append(title);
      make('text',{x:Math.max(65,Math.min(740,p.x+10)),y:23,fill:'#f6c56f','font-size':12}).textContent='EXIT PENDING';
    }
    for(const fill of model.markers)root.PaperTradeMarkers.drawFill(document,svg,fill);
    if(model.points.length||model.markers.length){
      make('text',{x:8,y:24,fill:'#adc0d6','font-size':12}).textContent=money(model.high);
      make('text',{x:8,y:225,fill:'#adc0d6','font-size':12}).textContent=money(model.low);
      for(const [time,x]of [[model.from,65],[model.to,620]])make('text',{x,y:252,fill:'#adc0d6','font-size':11}).textContent=new Date(time).toLocaleString('en-IN',{timeZone:'Asia/Kolkata',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});
    }
    $('auto-chart-note').textContent=(value.dataStatus||((value.bars||[]).length+' sourced completed candles.'))+(pending.length?' Exit pending — '+pending.map(p=>p.side+' '+p.quantity+' share(s)').join(', ')+'. No exit fill recorded.':'')+' Amber flags show pending requests; blue ▲ BUY and purple ▼ SELL triangles show recorded fills, not market direction. Longs exit at the bid; shorts cover at the ask. P&L uses fresh executable quotes.'+(value.outsideWindowMarkers?' '+value.outsideWindowMarkers+' older fill markers are outside this chart window; see position details.':'');
  }
  async function chart(version){if(overviewInteraction.dragging)return;const symbol=$('auto-chart-symbol').value;if(!symbol)return;const value=await api('/auto/chart?symbol='+encodeURIComponent(symbol));if(version===generation&&symbol===$('auto-chart-symbol').value&&!overviewInteraction.dragging)draw(value);}
  function renderPositions(){
    const query=workspaceLayout?$('auto-position-search').value.trim().toLowerCase():'',filter=workspaceLayout?$('auto-position-filter').value:'all';
    const isOpen=item=>['POSITION_OPEN','EXIT_PENDING','ENTRY_PENDING'].includes(item.state);
    const filtered=lastPositions.filter(item=>(!query||(item.symbol+' '+item.intentId).toLowerCase().includes(query))&&(filter==='all'||filter==='open'&&isOpen(item)||filter==='closed'&&item.state==='POSITION_CLOSED'||filter==='unfilled'&&!(item.filledQuantity>0)));
    positionPage=Math.min(positionPage,Math.max(0,Math.ceil(filtered.length/50)-1));
      const rows=$('auto-positions');rows.replaceChildren();
      for(const item of filtered.slice(positionPage*50,(positionPage+1)*50)){
        const row=text(rows,'tr',''),closed=item.state==='POSITION_CLOSED',filled=item.filledQuantity>0;
        const stateLabel={POSITION_OPEN:'Open',EXIT_PENDING:'Exit pending',POSITION_CLOSED:'Closed',REJECTED:'Rejected'}[item.state]||String(item.state||'').replaceAll('_',' ');
        const short=item.positionSide==='SHORT',pending=item.state==='EXIT_PENDING',side=pending?(short?'BUY / Cover pending':'SELL / Exit pending'):closed?(short?'BUY / Cover':'SELL / Exit'):(short?'SELL / Short':'BUY / Long');
        const first=text(row,'td',item.symbol+' · '+(filled?(pending?'♦ ':'● ')+side+' · '+stateLabel:stateLabel));first.className=pending?'auto-pending':filled?((closed?!short:short)?'auto-sell':'auto-buy'):'';
        text(row,'td',closed?(item.filledQuantity??0):(item.remainingQuantity??item.quantity??0));text(row,'td',filled?money(item.entryPrice):'—');
        text(row,'td',closed?money(item.exitPrice):filled?money(item.lastPrice):'—');
        const amount=text(row,'td','');if(filled)pnl(amount,closed?item.realizedPnl:item.unrealizedPnl);else amount.textContent='—';
        const actions=text(row,'td',''),details=text(actions,'button','Details');details.onclick=()=>root.WorkspaceShell?.openPosition?root.WorkspaceShell.openPosition(item.intentId):showPosition(item);
        if(['POSITION_OPEN','EXIT_PENDING','ENTRY_PENDING'].includes(item.state)){const exit=text(actions,'button','Exit');exit.setAttribute('data-requires-connection','');exit.onclick=()=>act(exit,async()=>{await api('/positions/'+encodeURIComponent(item.intentId)+'/close',{});await refresh();});}
      }
      if(!filtered.length)text(text(rows,'tr',''),'td','No positions match this view. A qualifying decision and risk approval are required for a new entry.');

    if(workspaceLayout){$('auto-position-count').textContent=filtered.length+' matching / '+lastPositions.length+' total records · Page '+(positionPage+1)+' of '+Math.max(1,Math.ceil(filtered.length/50));$('auto-position-prev').disabled=positionPage===0;$('auto-position-next').disabled=(positionPage+1)*50>=filtered.length;}
  }
  // A separate read-only flow window can keep status updates active without
  // loading charts or market overview while the workspace tab is hidden.
  const statusViewVisible=()=>!document.hidden||Boolean(root.SystemFlowWindow?.isOpen());
  function refresh(){
    if(activeRefresh)return activeRefresh;
    if(!token||authError||!statusViewVisible())return Promise.resolve();
    const work=performRefresh();activeRefresh=work;
    work.finally(()=>{if(activeRefresh===work)activeRefresh=null;});return work;
  }
  async function performRefresh(){
    if(!token||authError||!statusViewVisible())return;running=true;const version=generation;
    try{
      const value=await api('/auto/status');if(version!==generation)return;
      const a=value.automation,p=value.portfolio,r=value.risk;
      $('auto-mode').value=a.mode||'PAPER';
      if(!a||!p||!r?.policy||!r?.calendar||!Array.isArray(p.positions))throw Error('The trading service returned incomplete workspace data.');
      lastPositions=p.positions;
      if(workspaceLayout)$('auto-start').textContent=a.enabled?'Apply auto settings':'Start auto mode';
      if(!eventDirty){$('auto-event').checked=Boolean(a.scheduledEventRisk);$('auto-event-help').textContent=a.scheduledEventRisk?'Manual event pause is ON. Automatic stock analysis and new entries are blocked. Review this setting to resume normal checks.':'Manual event pause is off. Normal news, data and entry-risk checks apply.';}
      const statusText=(a.enabled?'Auto on':'Auto off')+' · '+(root.WorkspaceShell?.humanState(a.state)||a.state)+' · '+messageText(a.message)+(a.busy?' · Working…':'');
      if($('auto-status').textContent!==statusText)$('auto-status').textContent=statusText;
      if($('auto-connection-state'))$('auto-connection-state').textContent='Connected successfully.';
      $('auto-rules').textContent='Effective capital '+money(r.policy.capital)+' · risk/trade '+r.policy.riskPerTradePercent+'% of exposure'+(r.riskPerTradeLimit!=null?' ('+money(r.riskPerTradeLimit)+')':'')+' · daily loss '+r.policy.dailyLossPercent+'% of configured intraday exposure'+(r.dailyLossLimit!=null?' ('+money(r.dailyLossLimit)+')':'')+' · max '+r.policy.maxTradesPerDay+' entries/day · session '+(r.calendar.verified?(r.calendar.sessionOpen?'open':'closed'):'not verified')+'. '+(r.calendar.reason||'')+(r.calendar.entryEndsAt?' Entry window '+r.calendar.open+'–'+r.calendar.entryEndsAt+' IST.':'')+(r.calendar.nextEntrySession?' Next: '+r.calendar.nextEntrySession+'.':'')+' Dashboard and platform limits both apply; the stricter value wins.';
      pnl($('auto-realized'),p.realizedPnl);pnl($('auto-unrealized'),p.unrealizedPnl);pnl($('auto-net'),p.netPnl);
      const footerKey=JSON.stringify([a.mode,a.enabled,p.netPnl,p.realizedPnl,p.unrealizedPnl,p.hasStaleMarks]);
      if(footerKey!==lastFooter){lastFooter=footerKey;bar.replaceChildren();text(bar,'b',(a.mode||'PAPER')+' · '+(a.enabled?'AUTO ON':'AUTO OFF'));pnl(text(bar,'strong',''),p.netPnl);
        text(bar,'span','Realized '+money(p.realizedPnl)+' · Open '+money(p.unrealizedPnl));text(bar,'span',p.hasStaleMarks?'STALE MARK — last known P&L':'Estimated costs included');}
      lastValue=value;connection({state:'connected',updatedAt:new Date().toISOString()});for(const callback of subscribers){try{callback(value);}catch(error){console.warn('Workspace view could not update.',error);}}
      $('auto-evidence').textContent=JSON.stringify({state:a.state,session:r.calendar,reference:a.referenceMessage,selection:a.selection,decision:a.decision,lastIntentId:a.lastIntentId},null,2);
      const symbols=[...new Set([...(a.newsPlan?.candidates||[]).map(x=>x.symbol),a.selection?.selected?.symbol,...lastPositions.filter(x=>x.filledQuantity>0).map(x=>x.symbol),a.referenceSymbol].filter(Boolean))];
      const select=$('auto-chart-symbol'),old=select.value,symbolKey=symbols.join('|');
      if(symbolKey!==previousSymbols){previousSymbols=symbolKey;select.replaceChildren();for(const symbol of symbols){const option=text(select,'option',symbol===a.referenceSymbol?symbol+' · reference index':symbol);option.value=symbol;}select.value=symbols.includes(old)?old:(symbols[0]||'');}
      selected=select.value;
      renderPositions();
      if(!document.hidden&&(!workspaceLayout||!$('workspace-chart').closest('[data-screen]').hidden))try{await chart(version);}catch(error){if(error.status===401)throw error;$('auto-chart-note').hidden=false;$('auto-chart-note').textContent='Chart unavailable: '+error.message;}
    }catch(error){if(version===generation){lastFooter='';connection({state:'error',message:error.message,status:error.status});$('auto-status').textContent=error.message+' No successful update received.';if($('auto-connection-state'))$('auto-connection-state').textContent=error.message;if(root.WorkspaceShell&&$('workspace-connection-status'))$('workspace-connection-status').textContent='Connection needs attention';bar.textContent=(lastValue?.automation.mode||'PAPER')+' · Update unavailable'+(lastValue?' · Last known net '+money(lastValue.portfolio.netPnl)+' · P&L may be stale':' · Connect to load P&L');}}
    finally{running=false;}
  }
  async function act(button,action){if(button.dataset?.busy)return;if(button.dataset)button.dataset.busy='true';button.disabled=true;try{await action();}catch(error){$('auto-status').textContent=error.message;root.toast?.(error.message);}finally{if(button.dataset)delete button.dataset.busy;button.disabled=Boolean(workspaceLayout&&button.hasAttribute('data-requires-connection')&&connectionState.state!=='connected');}}
  async function connect(value){
    token=String(value||'').trim();authError=false;generation++;eventDirty=false;lastValue=null;root.WorkspaceAPI?.setToken(token);root.WorkspaceResearch?.reset();$('auto-token').value='';
    if(!token){connection({state:'disconnected'});return;}
    connection({state:'connecting'});if($('auto-connection-state'))$('auto-connection-state').textContent='Connecting…';
    if(activeRefresh)await activeRefresh;await refresh();
  }
  function disconnect(){token='';generation++;lastValue=null;lastPositions=[];authError=false;$('auto-confirm').checked=false;root.WorkspaceAPI?.setToken('');bar.textContent='Disconnected · Last displayed data may be stale';connection({state:'disconnected'});}

  $('auto-connect').onclick=()=>act($('auto-connect'),()=>connect($('auto-token').value));
  $('auto-start').onclick=()=>act($('auto-start'),async()=>{
    if(!token)throw Error('Connect with the shared token first.');if(!$('auto-confirm').checked)throw Error('Confirm automatic entries for the selected mode first.');
    await api('/auto/start',{mode:$('auto-mode').value,confirmed:true,scheduledEventRisk:$('auto-event').checked});eventDirty=false;await refresh();
  });
  $('auto-event').onchange=()=>{
    if(!token){$('auto-event').checked=Boolean(lastValue?.automation.scheduledEventRisk);return;}
    const version=generation,requested=$('auto-event').checked;eventDirty=true;$('auto-event-help').textContent='Saving the manual event pause…';
    return act($('auto-event'),async()=>{
      try{const saved=await api('/auto/event-risk',{enabled:requested});if(version!==generation)return;eventDirty=false;$('auto-event').checked=Boolean(saved.scheduledEventRisk);await refresh();}
      catch(error){if(version===generation){eventDirty=false;$('auto-event').checked=Boolean(lastValue?.automation.scheduledEventRisk);$('auto-event-help').textContent='Could not save this change. Showing the last confirmed manual-pause setting.';}throw error;}
    });
  };
  $('auto-stop').onclick=()=>act($('auto-stop'),async()=>{await api('/auto/stop',{});await refresh();});
  $('auto-exit').onclick=()=>act($('auto-exit'),async()=>{await api('/auto/stop',{});const state=await api('/auto/status');for(const item of state.portfolio.positions)if(['POSITION_OPEN','EXIT_PENDING','ENTRY_PENDING'].includes(item.state))await api('/positions/'+encodeURIComponent(item.intentId)+'/close',{});await refresh();});
  $('auto-chart-symbol').onchange=()=>chart(generation).catch(error=>{$('auto-chart-note').textContent=error.message;});
  async function modeChanged(){overviewInteraction.cancel?.();overviewViews.clear();$('overview-candles')?.replaceChildren();generation++;lastValue=null;lastPositions=[];previousSymbols='';lastFooter='';root.WorkspaceAPI?.invalidateReads?.();
    if(activeRefresh)await activeRefresh;return refresh();}
  root.AutoPaperUI={connect,disconnect,refresh,modeChanged,showPosition(id){if(!lastValue)return null;const item=lastPositions.find(row=>row.intentId===id);if(item)showPosition(item);return Boolean(item);},subscribeConnection(callback){connectionSubscribers.add(callback);callback(connectionState);return()=>connectionSubscribers.delete(callback);},subscribe(callback){subscribers.add(callback);if(lastValue)callback(lastValue);return()=>subscribers.delete(callback);},
    async updatePolicy(policy,admin){await api('/risk/policy',policy,'PUT',admin);await refresh();},
    async updateIntraday(rules,admin){await api('/risk/intraday',rules,'PUT',admin);await refresh();},
    async setKillSwitch(enabled,admin){await api('/risk/kill-switch',{enabled},'POST',admin);await refresh();}};
  if(workspaceLayout){
    for(const id of ['auto-position-search','auto-position-filter'])$(id).addEventListener('input',()=>{positionPage=0;renderPositions();});
    $('auto-position-prev').onclick=()=>{positionPage=Math.max(0,positionPage-1);renderPositions();};$('auto-position-next').onclick=()=>{positionPage++;renderPositions();};
  }
  setInterval(refresh,5000);
})(globalThis);
