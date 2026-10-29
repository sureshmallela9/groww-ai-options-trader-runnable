(function(root){
  'use strict';
  function chartModel(bars,markers,width=900,height=260){
    const points=bars.map(b=>({time:Date.parse(b.timestamp),price:Number(b.close)}))
      .filter(p=>Number.isFinite(p.time)&&Number.isFinite(p.price)&&p.price>0).sort((a,b)=>a.time-b.time);
    const fills=markers.filter(m=>['BUY','SELL'].includes(m.side)).map(m=>({...m,time:Date.parse(m.time),price:Number(m.price)}))
      .filter(p=>Number.isFinite(p.time)&&Number.isFinite(p.price)&&p.price>0);
    const all=points.concat(fills);if(!all.length)return {points:[],markers:[]};
    const from=Math.min(...all.map(p=>p.time)),to=Math.max(...all.map(p=>p.time));
    const low=Math.min(...all.map(p=>p.price)),high=Math.max(...all.map(p=>p.price));
    const range=Math.max(high-low,high*.002,0.01);
    const place=p=>({...p,x:65+(p.time-from)/Math.max(1,to-from)*(width-90),
      y:20+(high+range*.1-p.price)/(range*1.2)*(height-55)});
    return {points:points.map(place),markers:fills.map(place),low,high,from,to};
  }
  if(typeof module==='object'&&module.exports){module.exports={chartModel};return;}
  const host=document.getElementById('automatic-paper');if(!host)return;
  host.innerHTML=`<div class="auto-heading"><div><h2>Automatic paper trading</h2><p>Validate → analyze NIFTY 50 → select one stock → recheck risk → simulate entry. Five-second checks; a new scan follows each completed 5-minute candle.</p></div><span class="auto-badge">PAPER · live execution disabled</span></div>
  <div class="auto-connection"><label>Connection token <input id="auto-token" type="password" autocomplete="off" placeholder="Shared INTERNAL_API_TOKEN"></label><button id="auto-connect">Connect</button></div>
  <div class="auto-controls"><label>Execution mode <select id="auto-mode"><option value="PAPER">Paper simulation</option value="LIVE" disabled>Real orders · not available</option></select></label>
  <label><input id="auto-event" type="checkbox"> Important scheduled event risk — blocks entries</label>
  <label><input id="auto-confirm" type="checkbox"> Allow automatic simulated paper entries under the configured rules</label>
  <button id="auto-start">Start / apply paper auto mode</button><button id="auto-stop">Stop new entries</button><button id="auto-exit" class="auto-danger">Stop and exit paper positions</button></div>
  <p id="auto-status" role="status">Connect to load automatic paper positions and P&amp;L.</p><p id="auto-rules" class="auto-muted"></p>
  <div class="auto-metrics"><div>Today's realized P&amp;L<strong id="auto-realized">—</strong></div><div>Open P&amp;L<strong id="auto-unrealized">—</strong></div><div>Today's net P&amp;L<strong id="auto-net">—</strong></div></div>
  <div class="auto-chart-heading"><h3>Selected stock and paper fills</h3><label>Stock <select id="auto-chart-symbol"><option value="">Waiting for selection</option></select></label><span><b class="auto-buy">● BUY</b> &nbsp; <b class="auto-sell">● SELL / EXIT</b></span></div>
  <svg id="auto-chart" viewBox="0 0 900 260" role="img" aria-label="Stock price history with green buy and red sell fill markers"></svg><p id="auto-chart-note" class="auto-muted">Recorded fills appear only after a simulated entry or exit is completed.</p>
  <div class="auto-table"><table><thead><tr><th>Stock / position</th><th>Quantity</th><th>Entry</th><th>Exit / latest</th><th>Net P&amp;L</th><th>Action</th></tr></thead><tbody id="auto-positions"></tbody></table></div>
  <details><summary>Selection, decision and rule evidence</summary><pre id="auto-evidence">No automatic selection yet.</pre></details>
  <p class="auto-muted">Runs on the server until stopped; a restart pauses automatic entries. Existing positions remain under the risk monitor. P&amp;L includes configured estimated costs. Industry confirmation uses a labelled derived basket of maintained constituents, not an exchange sector index. <a href="/paper-workspace.html">Detailed paper workspace</a></p>`;
  const style=document.createElement('style');style.textContent=`
  #automatic-paper{padding:22px;background:#111e2c;border:1px solid #33475e;border-radius:16px;margin:18px 0;color:#eef4ff}#automatic-paper h2,#automatic-paper h3{margin:0 0 8px}#automatic-paper p{line-height:1.5}.auto-heading,.auto-chart-heading,.auto-controls,.auto-connection{display:flex;gap:14px;align-items:center;flex-wrap:wrap}.auto-heading{justify-content:space-between}.auto-heading p{max-width:760px;color:#aabbcf}.auto-controls{margin:18px 0}.auto-controls label{display:flex;align-items:center;gap:8px;font-weight:400}.auto-controls input[type=checkbox]{width:auto;margin:0}.auto-connection input,.auto-controls select,#auto-chart-symbol{background:#0a1522;border:1px solid #45607a;border-radius:7px;color:white;padding:9px}.auto-connection input{width:250px;max-width:100%;margin-left:8px}#automatic-paper button{padding:10px 13px;background:#254c45;border:1px solid #427568;border-radius:8px;color:#f1fffa;cursor:pointer}#automatic-paper button:disabled{opacity:.5;cursor:wait}.auto-danger{background:#5b2634!important}.auto-badge{color:#7ee6c0;font-size:12px}.auto-muted{color:#aabbcf;font-size:12px}.auto-metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin:18px 0}.auto-metrics>div{border:1px solid #30465d;border-radius:10px;padding:14px;color:#aabbcf}.auto-metrics strong{display:block;font-size:24px;color:#edf5ff;margin-top:5px}.auto-buy,.auto-profit{color:#38d9a9!important}.auto-sell,.auto-loss{color:#ff7185!important}.auto-table{overflow:auto}#automatic-paper table{width:100%;border-collapse:collapse}#automatic-paper td,#automatic-paper th{padding:10px;text-align:left;border-bottom:1px solid #2d435b;white-space:nowrap}#auto-chart{width:100%;height:auto;min-height:180px;background:#091421;border-radius:10px;margin-top:10px}#automatic-paper pre{white-space:pre-wrap;max-height:450px;overflow:auto;font-size:12px}#auto-pnl-bar{position:fixed;left:0;right:0;bottom:0;z-index:40;background:#102332;border-top:1px solid #42617a;padding:12px 20px;display:flex;gap:18px;justify-content:center;flex-wrap:wrap;color:#edf5ff;font:14px system-ui}body{padding-bottom:90px!important}@media(max-width:650px){.auto-metrics{grid-template-columns:1fr}.auto-metrics strong{font-size:22px}.auto-connection input{margin-left:0;width:100%}#automatic-paper{padding:15px}#auto-pnl-bar{font-size:12px;padding:8px;gap:9px}}
  `;document.head.append(style);
  const bar=document.createElement('div');bar.id='auto-pnl-bar';bar.setAttribute('aria-live','polite');bar.textContent='PAPER P&L · Connect to view';document.body.append(bar);
  const $=id=>document.getElementById(id);let token='',running=false,generation=0,selected='',lastPositions=[],eventDirty=false;
  const money=v=>new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(Number(v||0));
  function text(parent,tag,value){const el=document.createElement(tag);el.textContent=value;parent.append(el);return el;}
  function pnl(el,value){el.textContent=money(value);el.classList.toggle('auto-profit',Number(value)>=0);el.classList.toggle('auto-loss',Number(value)<0);}
  async function api(path,body){
    const response=await fetch('/api/v1'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+token},body:body===undefined?undefined:JSON.stringify(body)});
    const value=await response.json();if(!response.ok)throw Error(value.error||value.detail||'Paper service unavailable.');return value;
  }
  function draw(value){
    const svg=$('auto-chart');svg.replaceChildren();const model=chartModel(value.bars||[],value.markers||[]);
    const make=(name,attrs)=>{const e=document.createElementNS('http://www.w3.org/2000/svg',name);for(const [k,v]of Object.entries(attrs))e.setAttribute(k,v);svg.append(e);return e;};
    if(model.points.length)make('path',{d:model.points.map((p,i)=>(i?'L':'M')+p.x.toFixed(2)+','+p.y.toFixed(2)).join(' '),fill:'none',stroke:'#819dff','stroke-width':2.5});
    for(const fill of model.markers){
      const color=fill.side==='BUY'?'#38d9a9':'#ff7185';
      const circle=make('circle',{cx:fill.x,cy:fill.y,r:6,fill:color,stroke:'#fff','stroke-width':1});
      const title=document.createElementNS('http://www.w3.org/2000/svg','title');title.textContent=fill.side+' '+money(fill.price)+' · '+new Date(fill.time).toLocaleString('en-IN',{timeZone:'Asia/Kolkata'});circle.append(title);
      make('text',{x:Math.min(820,Math.max(65,fill.x+9)),y:Math.max(15,fill.y-9),fill:color,'font-size':12}).textContent=fill.side;
    }
    if(model.points.length||model.markers.length){
      make('text',{x:8,y:24,fill:'#adc0d6','font-size':12}).textContent=money(model.high);
      make('text',{x:8,y:225,fill:'#adc0d6','font-size':12}).textContent=money(model.low);
      for(const [time,x]of [[model.from,65],[model.to,620]])make('text',{x,y:252,fill:'#adc0d6','font-size':11}).textContent=new Date(time).toLocaleString('en-IN',{timeZone:'Asia/Kolkata',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'});
    }
    $('auto-chart-note').textContent=(value.dataStatus||((value.bars||[]).length+' sourced completed candles.'))+' Buy/exit dots show simulated filled prices. Candle history updates with new five-minute candles; current open P&L updates from fresh bid quotes.'+(value.outsideWindowMarkers?' '+value.outsideWindowMarkers+' older fill markers are outside this chart window; see position details.':'');
  }
  async function chart(version){const symbol=$('auto-chart-symbol').value;if(!symbol)return;const value=await api('/auto/chart?symbol='+encodeURIComponent(symbol));if(version===generation&&symbol===$('auto-chart-symbol').value)draw(value);}
  async function refresh(){
    if(!token||running||document.hidden)return;running=true;const version=generation;
    try{
      const value=await api('/auto/status');if(version!==generation)return;
      const a=value.automation,p=value.portfolio,r=value.risk;lastPositions=p.positions||[];
      if(!eventDirty)$('auto-event').checked=Boolean(a.scheduledEventRisk);
      $('auto-status').textContent=(a.enabled?'AUTO ON':'AUTO OFF')+' · '+a.state+' · '+(a.message||'')+(a.busy?' · Working…':'');
      $('auto-rules').textContent='Effective capital '+money(r.policy.capital)+' · risk/trade '+r.policy.riskPerTradePercent+'% · daily loss '+r.policy.dailyLossPercent+'% · max '+r.policy.maxTradesPerDay+' entries/day · session '+(r.calendar.verified?(r.calendar.sessionOpen?'open':'closed'):'not verified')+'. '+(r.calendar.reason||'')+(r.calendar.entryEndsAt?' Entry window '+r.calendar.open+'–'+r.calendar.entryEndsAt+' IST.':'')+(r.calendar.nextEntrySession?' Next: '+r.calendar.nextEntrySession+'.':'')+' Dashboard and platform limits both apply; the stricter value wins.';
      pnl($('auto-realized'),p.realizedPnl);pnl($('auto-unrealized'),p.unrealizedPnl);pnl($('auto-net'),p.netPnl);
      bar.replaceChildren();text(bar,'b','PAPER · '+(a.enabled?'AUTO ON':'AUTO OFF'));pnl(text(bar,'strong',''),p.netPnl);
      text(bar,'span','Realized '+money(p.realizedPnl)+' · Open '+money(p.unrealizedPnl));text(bar,'span',p.hasStaleMarks?'STALE MARK — last known P&L':'Estimated costs included');
      $('auto-evidence').textContent=JSON.stringify({state:a.state,session:r.calendar,reference:a.referenceMessage,selection:a.selection,decision:a.decision,lastIntentId:a.lastIntentId},null,2);
      const symbols=[...new Set([a.selection?.selected?.symbol,...lastPositions.filter(x=>x.filledQuantity>0).map(x=>x.symbol),a.referenceSymbol].filter(Boolean))];
      const select=$('auto-chart-symbol'),old=select.value;select.replaceChildren();for(const symbol of symbols){const option=text(select,'option',symbol===a.referenceSymbol?symbol+' · reference index (no stock selected)':symbol);option.value=symbol;}
      select.value=symbols.includes(old)?old:(symbols[0]||'');selected=select.value;
      const rows=$('auto-positions');rows.replaceChildren();
      for(const item of lastPositions.slice(0,50)){
        const row=text(rows,'tr',''),closed=item.state==='POSITION_CLOSED',filled=item.filledQuantity>0;
        const first=text(row,'td',item.symbol+' · '+(filled?(closed?'● SELL / CLOSED':'● BUY / '+item.state):item.state));first.className=filled?(closed?'auto-sell':'auto-buy'):'';
        text(row,'td',item.remainingQuantity??item.quantity??0);text(row,'td',filled?money(item.entryPrice):'—');
        text(row,'td',closed?money(item.exitPrice):filled?money(item.lastPrice):'—');
        const amount=text(row,'td','');if(filled)pnl(amount,closed?item.realizedPnl:item.unrealizedPnl);else amount.textContent='—';
        const actions=text(row,'td',''),details=text(actions,'button','Details');details.onclick=()=>{$('auto-evidence').textContent=JSON.stringify(item,null,2);};
        if(['POSITION_OPEN','EXIT_PENDING'].includes(item.state)){const exit=text(actions,'button','Exit');exit.onclick=()=>act(exit,async()=>{await api('/paper/positions/'+encodeURIComponent(item.intentId)+'/close',{});await refresh();});}
      }
      if(!lastPositions.length)text(text(rows,'tr',''),'td','No paper positions yet. A qualifying decision and risk approval are required.');
      await chart(version);
    }catch(error){if(version===generation){$('auto-status').textContent=error.message+' No successful update received.';bar.textContent='PAPER · service update unavailable · '+bar.textContent.replace(/^PAPER · service update unavailable · /,'');}}
    finally{running=false;}
  }
  async function act(button,action){button.disabled=true;try{await action();}catch(error){$('auto-status').textContent=error.message;}finally{button.disabled=false;}}
  async function connect(value){token=value.trim();generation++;$('auto-token').value='';await refresh();}
  $('auto-connect').onclick=()=>act($('auto-connect'),()=>connect($('auto-token').value));
  $('auto-start').onclick=()=>act($('auto-start'),async()=>{
    if(!token)throw Error('Connect with the shared token first.');if(!$('auto-confirm').checked)throw Error('Tick the automatic paper-entry confirmation first.');
    await api('/auto/start',{mode:$('auto-mode').value,confirmed:true,scheduledEventRisk:$('auto-event').checked});eventDirty=false;await refresh();
  });
  $('auto-event').onchange=()=>{eventDirty=true;if(token)act($('auto-event'),async()=>{
    await api('/auto/event-risk',{enabled:$('auto-event').checked});eventDirty=false;await refresh();
  });};
  $('auto-stop').onclick=()=>act($('auto-stop'),async()=>{await api('/auto/stop',{});await refresh();});
  $('auto-exit').onclick=()=>act($('auto-exit'),async()=>{await api('/auto/stop',{});const state=await api('/auto/status');for(const item of state.portfolio.positions)if(['POSITION_OPEN','EXIT_PENDING'].includes(item.state))await api('/paper/positions/'+encodeURIComponent(item.intentId)+'/close',{});await refresh();});
  $('auto-chart-symbol').onchange=()=>chart(generation).catch(error=>{$('auto-chart-note').textContent=error.message;});
  root.AutoPaperUI={connect};setInterval(refresh,5000);
})(globalThis);
