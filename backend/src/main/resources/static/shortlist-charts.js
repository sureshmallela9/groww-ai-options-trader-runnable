(function(root){
  'use strict';
  const tradeMarkers=root.PaperTradeMarkers||(typeof module==='object'&&module.exports?require('./trade-markers.js'):null);
  const candleView=root.CandleView||(typeof module==='object'&&module.exports?require('./candle-view.js'):null);
  const COLORS={BULLISH:'#38d9a9',BEARISH:'#ff7185',NEUTRAL:'#f6c56f',WAITING:'#9baec5',STALE:'#f6c56f'};
  const numeric=v=>typeof v==='number'&&Number.isFinite(v);
  const time=v=>Number.isFinite(Date.parse(v))?new Intl.DateTimeFormat('en-IN',{timeZone:'Asia/Kolkata',day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(Date.parse(v))+' IST':'Waiting';
  const number=v=>numeric(v)?new Intl.NumberFormat('en-IN',{maximumFractionDigits:2}).format(v):'—';
  function signal(stock,now=Date.now(),running=true){
    if(!stock.analysis?.as_of)return 'WAITING';
    const close=Date.parse(stock.analysis.as_of)+300000;
    const received=Date.parse(stock.aiReceivedAt);
    if(!running||!Number.isFinite(received)||now-received>30000||!Number.isFinite(close)||now-close>600000||close>now)return 'STALE';
    return ({bullish:'BULLISH',bearish:'BEARISH',neutral:'NEUTRAL'})[stock.analysis?.trend]||'WAITING';
  }
  function candleModel(bars=[],markers=[],limit=75,pending=[],view={}){
    const valid=bars.filter(b=>Number.isFinite(Date.parse(b.timestamp))&&['open','high','low','close','volume'].every(k=>numeric(b[k]))&&b.low>0&&b.high>=Math.max(b.open,b.close)&&b.low<=Math.min(b.open,b.close)&&b.volume>=0).slice().sort((a,b)=>Date.parse(a.timestamp)-Date.parse(b.timestamp));
    const count=limit?Math.max(1,Math.min(valid.length,Math.floor(limit))):valid.length;
    const anchor=Date.parse(view.endTime),requestedEnd=Number.isFinite(anchor)?valid.filter(b=>Date.parse(b.timestamp)<=anchor).length:valid.length;
    const endIndex=Math.max(count,Math.min(valid.length,requestedEnd));
    const shown=valid.slice(Math.max(0,endIndex-count),endIndex);if(!shown.length)return {candles:[],markers:[],pending:[],total:valid.length};
    const atLatest=endIndex===valid.length;
    const barEnd=b=>Number.isFinite(Date.parse(b.endTimestamp))?Date.parse(b.endTimestamp):Date.parse(b.timestamp)+300000;
    const start=Date.parse(shown[0].timestamp),end=barEnd(shown.at(-1));
    const eligible=markers.filter(m=>['BUY','SELL'].includes(m.side)&&numeric(m.price)&&m.price>0&&Date.parse(m.time)>=start&&Date.parse(m.time)<=Date.now()+5000&&(atLatest||Date.parse(m.time)<end));
    const requests=pending.filter(p=>Number.isFinite(Date.parse(p.requestedAt))&&Date.parse(p.requestedAt)>=start&&Date.parse(p.requestedAt)<=Date.now()+5000&&(atLatest||Date.parse(p.requestedAt)<end));
    const trailing=eligible.some(m=>Date.parse(m.time)>=end)||requests.some(p=>Date.parse(p.requestedAt)>=end);
    const low=Math.min(...shown.map(b=>b.low),...eligible.map(m=>m.price)),high=Math.max(...shown.map(b=>b.high),...eligible.map(m=>m.price)),range=Math.max(high-low,high*.002,.01);
    const y=price=>18+(high+range*.1-price)/(range*1.2)*210;
    const step=800/(shown.length+(trailing?1:0)),x=index=>65+(index+.5)*step;
    const candles=shown.map((b,i)=>({...b,x:x(i),top:y(Math.max(b.open,b.close)),bottom:y(Math.min(b.open,b.close)),highY:y(b.high),lowY:y(b.low),width:Math.max(.7,Math.min(12,step*.65)),color:b.close>=b.open?COLORS.BULLISH:COLORS.BEARISH}));
    const fills=eligible.map(m=>{
      let index=shown.findIndex(b=>barEnd(b)>Date.parse(m.time));if(index<0)index=shown.length;
      return {...m,x:x(index),y:y(m.price)};
    });
    const flags=requests.map(p=>{let i=shown.findIndex(b=>barEnd(b)>Date.parse(p.requestedAt));return {...p,x:x(i<0?shown.length:i)};});
    const stepBack=Math.max(1,Math.floor(count/2));
    return {candles,markers:fills,pending:flags,total:valid.length,low,high,from:shown[0].timestamp,to:shown.at(-1).timestamp,
      endIndex,timeline:valid.map(b=>b.timestamp),atLatest,atOldest:endIndex===count,latestTime:valid.at(-1).timestamp,
      olderEndTime:valid[Math.max(count,endIndex-stepBack)-1].timestamp,
      newerEndTime:valid[Math.min(valid.length,endIndex+stepBack)-1].timestamp};
  }
  function drawChart(doc,svg,model,stock,frame){
    svg.replaceChildren();
    const make=(tag,attrs)=>{const el=doc.createElementNS('http://www.w3.org/2000/svg',tag);for(const [k,v]of Object.entries(attrs))el.setAttribute(k,String(v));svg.append(el);return el;};
    if(!model.candles.length)return;
    const range=Math.max(model.high-model.low,model.high*.002,.01),max=model.high+range*.1,min=model.high-range*1.1;
    for(const y of [18,70.5,123,175.5,228]){
      make('line',{x1:65,x2:865,y1:y,y2:y,stroke:'#223342','stroke-width':1});
      make('text',{x:875,y:y+4,fill:'#adc0d6','font-size':12}).textContent=number(max-(y-18)/210*(max-min));
    }
    const volumeMax=Math.max(1,...model.candles.map(b=>b.volume));
    make('text',{x:65,y:248,fill:'#adc0d6','font-size':11}).textContent='Volume';
    make('text',{x:875,y:267,fill:'#adc0d6','font-size':11}).textContent=number(volumeMax);
    for(const b of model.candles){
      make('rect',{x:b.x-b.width/2,y:309-b.volume/volumeMax*48,width:b.width,height:b.volume/volumeMax*48,fill:b.color,opacity:.6});
      make('line',{x1:b.x,x2:b.x,y1:b.highY,y2:b.lowY,stroke:b.color,'stroke-width':1.2});
      const body=make('rect',{x:b.x-b.width/2,y:b.top,width:b.width,height:Math.max(1,b.bottom-b.top),fill:b.color,...(b.partial?{stroke:'#d6e0ed','stroke-width':1.5,'stroke-dasharray':'2 2'}:{})});
      const title=doc.createElementNS('http://www.w3.org/2000/svg','title');title.textContent=time(b.timestamp)+' · O '+number(b.open)+' H '+number(b.high)+' L '+number(b.low)+' C '+number(b.close)+' · V '+number(b.volume)+(b.partial?' · PARTIAL':'');body.append(title);
    }
    const last=model.candles.at(-1),lastY=18+(max-last.close)/(max-min)*210;
    make('line',{x1:65,x2:865,y1:lastY,y2:lastY,stroke:last.color,'stroke-dasharray':'3 4',opacity:.55});
    make('rect',{x:868,y:lastY-10,width:88,height:20,rx:3,fill:last.color});
    make('text',{x:873,y:lastY+4,fill:'#08141e','font-size':12,'font-weight':700}).textContent=number(last.close);
    for(const p of model.pending){
      make('line',{x1:p.x,x2:p.x,y1:32,y2:230,stroke:'#f6c56f','stroke-dasharray':'5 4','stroke-width':1.5});
      const flag=make('path',{d:'M '+p.x+' 12 l 7 9 l -7 9 l -7 -9 Z',fill:'#0d1824',stroke:'#f6c56f','stroke-width':2});
      const title=doc.createElementNS('http://www.w3.org/2000/svg','title');title.textContent='Exit pending · '+p.side+' '+p.quantity+' · '+time(p.requestedAt)+' · No fill';flag.append(title);
      make('text',{x:Math.max(65,Math.min(740,p.x+10)),y:23,fill:'#f6c56f','font-size':12}).textContent='EXIT PENDING';
    }
    for(const m of model.markers)tradeMarkers.drawFill(doc,svg,m);
    for(const tick of candleView.timeTicks(model.candles,frame.id)){
      make('line',{x1:tick.x,x2:tick.x,y1:18,y2:314,stroke:'#263b4c','stroke-dasharray':'2 5',opacity:.4});
      make('text',{x:tick.x,y:337,fill:'#d6e0ed','font-size':12,'text-anchor':'middle'}).textContent=tick.top;
      make('text',{x:tick.x,y:355,fill:'#9fb3c9','font-size':11,'text-anchor':'middle'}).textContent=tick.bottom;
    }
    make('text',{x:956,y:375,fill:'#9fb3c9','font-size':11,'text-anchor':'end'}).textContent='Price · INR / Time · IST';
  }
  function bindReadout(doc,svg,readout,getModel,interaction){
    const describe=b=>b?time(b.timestamp)+' · O '+number(b.open)+'   H '+number(b.high)+'   L '+number(b.low)+'   C '+number(b.close)+'   Vol '+number(b.volume)+(b.partial?' · Partial group':''): 'No candle selected';
    readout.textContent=describe(getModel().candles.at(-1));
    svg.addEventListener?.('pointermove',event=>{
      if(interaction.dragging)return;const model=getModel(),box=svg.getBoundingClientRect();
      const x=(event.clientX-box.left)/box.width*960,y=(event.clientY-box.top)/box.height*390;
      svg.querySelector?.('.candle-crosshair')?.remove();
      if(x<65||x>865||y<18||y>314||!model.candles.length){readout.textContent=describe(model.candles.at(-1));return;}
      const b=model.candles.reduce((a,b)=>Math.abs(a.x-x)<Math.abs(b.x-x)?a:b);
      readout.textContent=describe(b);const g=doc.createElementNS('http://www.w3.org/2000/svg','g');g.setAttribute('class','candle-crosshair');g.setAttribute('pointer-events','none');
      for(const attrs of [{x1:b.x,x2:b.x,y1:18,y2:314},{x1:65,x2:865,y1:y,y2:y}]){const line=doc.createElementNS('http://www.w3.org/2000/svg','line');for(const [k,v]of Object.entries({...attrs,stroke:'#b9cfe0','stroke-dasharray':'4 4',opacity:.75}))line.setAttribute(k,String(v));g.append(line);}svg.append(g);
    });
    svg.addEventListener?.('pointerleave',()=>{svg.querySelector?.('.candle-crosshair')?.remove();readout.textContent=describe(getModel().candles.at(-1));});
  }
  function renderDepth(parent,depth,text,now){
    const panel=text(parent,'section','','market-depth');panel.setAttribute('aria-label','Buy and sell order quantities');
    text(panel,'h4','Buy / sell balance · order book');
    const received=Date.parse(depth?.receivedAt),age=(now-received)/1000;
    const stale=depth?.stale===true||!Number.isFinite(age)||age>15||age < -2;
    const valid=depth?.available===true&&['buyPercent','sellPercent','buyQuantity','sellQuantity'].every(k=>numeric(depth[k])&&depth[k]>=0)&&depth.buyPercent<=100&&depth.sellPercent<=100&&Math.abs(depth.buyPercent+depth.sellPercent-100)<.02;
    if(valid){
      const values=text(panel,'div','','depth-values');text(values,'strong','Buying '+number(depth.buyPercent)+'%','depth-buy');text(values,'strong','Selling '+number(depth.sellPercent)+'%','depth-sell');
      const balance=text(panel,'div','','depth-balance');balance.setAttribute('role','img');balance.setAttribute('aria-label','Outstanding buying '+depth.buyPercent+' percent; selling '+depth.sellPercent+' percent');
      const buy=text(balance,'span','','depth-buy-fill');buy.style.width=depth.buyPercent+'%';const sell=text(balance,'span','','depth-sell-fill');sell.style.width=depth.sellPercent+'%';
      text(panel,'p','Buy quantity '+number(depth.buyQuantity)+' · Sell quantity '+number(depth.sellQuantity),'hint');
    }else text(panel,'p','Buy / sell percentages unavailable. '+(depth?.message||'Waiting for Groww order quantities.'),'hint');
    text(panel,'p',(stale?'Stale / unavailable snapshot':'Latest received snapshot')+' · '+time(depth?.receivedAt)+(depth?.sessionOpen===false?' · Market session closed':''),'depth-freshness'+(stale?' is-stale':''));
    text(panel,'p',depth?.basis==='VISIBLE_DEPTH'?'Visible depth only; full-market order quantities were unavailable.':'Outstanding order quantities, not executed buying/selling volume or a price prediction.','hint');
    if(depth?.warning)text(panel,'p',depth.warning,'hint');
    if((depth?.buy||[]).length||(depth?.sell||[]).length){
      const details=text(panel,'details','');text(details,'summary','Market depth · bid and ask quantities');
      const wrap=text(details,'div','','candle-table'),table=text(wrap,'table',''),head=text(text(table,'thead',''),'tr','');
      for(const label of ['Bid qty','Bid price ₹','Ask price ₹','Ask qty'])text(head,'th',label);
      const body=text(table,'tbody','');for(let i=0;i<Math.min(5,Math.max(depth.buy?.length||0,depth.sell?.length||0));i++){
        const row=text(body,'tr','');for(const v of [depth.buy?.[i]?.quantity,depth.buy?.[i]?.price,depth.sell?.[i]?.price,depth.sell?.[i]?.quantity])text(row,'td',number(v));
      }
    }
  }
  function render(host,data,{limit=75,now=Date.now(),views=new Map(),interaction={dragging:false},compact=false}={}){
    const doc=host.ownerDocument||document;
    const focusedSymbol=host.contains?.(doc.activeElement)?doc.activeElement?.closest?.('svg[data-chart-symbol]')?.dataset.chartSymbol:null;
    host.replaceChildren();
    const text=(parent,tag,value,cls)=>{const el=doc.createElement(tag);el.textContent=String(value??'');if(cls)el.className=cls;parent.append(el);return el;};
    if(!compact)text(host,'p',data.message||'Waiting for the first shortlist and candle handoff.','hint');
    if(!compact)text(host,'p',(data.processing?'● Processing':'● '+(data.state||'WAITING'))+' · AI received '+time(data.aiReceivedAt)+' · Target refresh: 5 seconds','hint');
    if(!(data.stocks||[]).length){text(host,'p','News or price-fallback stocks appear here after their first completed candle analysis.','info-note');return;}
    const grid=text(host,'div','','shortlist-grid');
    for(const stock of data.stocks){
      const view=views.get(stock.symbol)||{},frame=candleView.frameFor(view.frame),session=candleView.dayWindow(stock.bars,view.day,now),displayBars=candleView.aggregate(session.bars,frame.id);
      const dayState=view.day?{day:session.day}:{},dayLabel=day=>new Intl.DateTimeFormat('en-IN',{timeZone:'Asia/Kolkata',day:'2-digit',month:'short',year:'numeric'}).format(new Date(day+'T12:00:00+05:30'));
      const dayMarkers=(stock.markers||[]).filter(m=>candleView.dayOf(m.time)===session.day);
      const card=text(grid,'article','','shortlist-card'),heading=text(card,'div','','section-heading');
      if(!compact)text(heading,'h3',stock.symbol+' · '+frame.label);const state=signal(stock,now,data.state==='READY'&&data.automationEnabled!==false);
      if(!compact)text(heading,'strong','● '+({BULLISH:'Bullish evidence',BEARISH:'Bearish evidence',NEUTRAL:'Neutral evidence',STALE:'Stale / paused',WAITING:'Waiting for analysis'})[state],'candle-signal '+state.toLowerCase());
      if(!compact)text(card,'p',(stock.selectionSource==='PRICE_FALLBACK'?'Price fallback · Candle direction: ':'News direction: ')+(stock.newsSide==='SELL'?'SELL / SHORT':'BUY / LONG')+' · Entry check: '+String(stock.entryCheckStatus||'WAITING').replaceAll('_',' '),'hint');
      const pending=tradeMarkers?.pendingExits(data.positions||[],stock.symbol)||[];
      const dayPending=pending.filter(p=>candleView.dayOf(p.requestedAt)===session.day);
      for(const request of pending){
        const banner=text(card,'div','','exit-pending-banner');banner.setAttribute('role','status');
        text(banner,'strong','♦ Exit pending · '+request.side+' '+request.quantity+' share(s)');
        text(banner,'p','Requested '+(request.requestedAt?time(request.requestedAt):'time unavailable')+' · No exit fill recorded.');
        if(request.incident)text(banner,'p','Waiting: '+request.incident.toLowerCase().replaceAll('_',' ')+'.');
        text(banner,'small','Amber flags mark pending requests. Blue ▲ BUY and purple ▼ SELL triangles mark completed fills.');
      }
      let model=candleModel(displayBars,dayMarkers,view.limit??limit,dayPending,view);
      const controls=text(card,'div','','candle-zoom');controls.setAttribute('role','group');controls.setAttribute('aria-label',stock.symbol+' candle view controls');
      const apply=next=>{if(next)views.set(stock.symbol,next);else views.delete(stock.symbol);render(host,data,{limit,now,views,interaction,compact});};
      const button=(label,disabled,work)=>{const b=text(controls,'button',label,'text-button');b.type='button';b.disabled=disabled;b.onclick=work;return b;};
      const dayControls=text(card,'div','','candle-day-controls');dayControls.setAttribute('role','group');dayControls.setAttribute('aria-label',stock.symbol+' trading day');card.insertBefore?.(dayControls,controls);
      const dateLabel=text(dayControls,'label','Trading day · IST','field-label'),dateInput=text(dateLabel,'input','');dateInput.type='date';dateInput.value=session.day;dateInput.max=session.today;dateInput.setAttribute('aria-label',stock.symbol+' trading date');
      const selectDay=day=>apply({frame:frame.id,limit,...(day?{day}:{})});
      dateInput.onchange=()=>{const chosen=candleView.dayWindow(stock.bars,dateInput.value,now).day;selectDay(chosen);};
      const dayButton=(label,disabled,work)=>{const b=text(dayControls,'button',label,'text-button');b.type='button';b.disabled=disabled;b.onclick=work;};
      dayButton('← Previous day',!session.previous,()=>selectDay(session.previous));
      dayButton('Next day →',!session.next,()=>selectDay(session.next));
      dayButton('Today',!view.day,()=>selectDay(null));
      text(dayControls,'span',dayLabel(session.day)+(session.day===session.today?' · Today':''),'hint');
      if(!session.bars.length)text(card,'p','No completed candles for '+dayLabel(session.day)+' yet. Choose an earlier trading day to review available history.','info-note');
      const frameLabel=text(controls,'label','Candle timeframe','field-label');
      const frameSelect=text(frameLabel,'select','');frameSelect.setAttribute('aria-label',stock.symbol+' candle timeframe');
      for(const choice of candleView.FRAMES){const option=text(frameSelect,'option',choice.label);option.value=choice.id;}frameSelect.value=frame.id;
      frameSelect.onchange=()=>apply({...dayState,frame:frameSelect.value,limit:view.limit??limit});
      const minimumVisible=frame.id==='1d'?3:10;
      const anchored=nextLimit=>({...dayState,frame:frame.id,limit:nextLimit,...(!model.atLatest?{endTime:model.to}:{})});
      button('+ Zoom in',model.candles.length<=minimumVisible,()=>apply(anchored(Math.max(minimumVisible,Math.floor(model.candles.length/2)))));
      button('− Zoom out',model.candles.length>=model.total,()=>apply(anchored(Math.min(model.total,model.candles.length*2))));
      button('← Older',!model.candles.length||model.atOldest,()=>apply({...dayState,frame:frame.id,limit:model.candles.length,endTime:model.olderEndTime}));
      button('Newer →',!model.candles.length||model.atLatest,()=>apply({...dayState,frame:frame.id,limit:model.candles.length,...(model.newerEndTime!==model.latestTime?{endTime:model.newerEndTime}:{})}));
      button('Reset view',model.atLatest&&model.candles.length===Math.min(limit||model.total,model.total),()=>apply({...dayState,frame:frame.id,limit}));
      const viewNote=text(card,'p','','hint');
      if(!compact)text(card,'p','Drag within this trading day: right for older candles, left for newer. Use Previous day to review earlier sessions. Arrow keys work when the chart is focused.','candle-drag-help');
      if(!compact)text(card,'p',frame.id==='5m'?'Five-minute source candles. AI always uses the full five-minute history.':'Display grouped from five-minute candles; AI still uses the five-minute source. Dashed borders mark partial groups.','hint');
      const readout=text(card,'p','','candle-readout');readout.setAttribute('aria-label','Candle open high low close and volume');
      const svg=doc.createElementNS('http://www.w3.org/2000/svg','svg');
      svg.dataset.chartSymbol=stock.symbol;svg.setAttribute('viewBox','0 0 960 390');svg.setAttribute('tabindex','0');svg.setAttribute('role','img');svg.setAttribute('aria-label',stock.symbol+' '+frame.label+' candles. Drag or use arrow keys to move through history. Blue BUY up-triangles, purple SELL down-triangles and amber pending-exit flags');svg.classList.add('shortlist-candles');card.append(svg);
      const caption=text(card,'p','','hint');
      function describeView(){
        const partial=model.candles.filter(b=>b.partial).length;
        viewNote.textContent=(model.candles.length?(model.atLatest?(frame.id==='5m'?'Latest completed candles':'Latest chart view'):'Historical candle view'):'No completed candles to display')+(model.candles.length?' · '+time(model.from)+' → '+time(model.to):'');
        caption.textContent='Showing '+model.candles.length+' of '+model.total+' candles · '+dayLabel(session.day)+' · '+frame.label+' chart'+(compact?'':' · AI received '+(stock.aiCandleCount??'—')+' candles (5m source)')+' · '+model.markers.length+' completed fill(s) in view'+(partial?' · '+partial+' partial group(s)':'');
      }
      drawChart(doc,svg,model,stock,frame);describeView();bindReadout(doc,svg,readout,()=>model,interaction);
      renderDepth(card,stock.marketDepth,text,now);
      candleView.bindPan(svg,{interaction,getModel:()=>model,onPan:(endTime,atLatest)=>{
        const next={...dayState,frame:frame.id,limit:model.candles.length,...(!atLatest?{endTime}:{})};views.set(stock.symbol,next);
        model=candleModel(displayBars,dayMarkers,next.limit,dayPending,next);drawChart(doc,svg,model,stock,frame);describeView();readout.textContent='View changed · hover a candle to see OHLC and volume.';
      },onCommit:()=>render(host,data,{limit,now,views,interaction,compact})});
      if(!compact)text(card,'p','Groww fetched '+time(stock.receivedAt)+' · AI response '+time(stock.aiReceivedAt),'hint');
      if(stock.analysis?.as_of)text(card,'p','Latest completed candle starts '+time(stock.analysis.as_of)+' · '+(stock.analysis.summary||''),'hint');
      if(compact)continue;
      const latest=model.candles.at(-1),metrics=text(card,'div','','candle-metrics');
      for(const [name,v]of [['Open',latest?.open],['High',latest?.high],['Low',latest?.low],['Close',latest?.close],['Volume',latest?.volume],['AI RSI 14 (5m)',stock.analysis?.indicators?.rsi_14],['AI VWAP (5m)',stock.analysis?.indicators?.vwap]]){const cell=text(metrics,'div','');text(cell,'small',name);text(cell,'strong',number(v));}
      const reasons=[...(stock.entryReasons||[]),...(stock.analysis?.positive_reasons||[]),...(stock.analysis?.negative_reasons||[])].filter(v=>typeof v==='string');
      if(reasons.length){const ul=text(card,'ul','','hint');for(const reason of [...new Set(reasons)].slice(0,6))text(ul,'li',reason);}
      const details=text(card,'details','');details.dataset.symbol=stock.symbol;text(details,'summary','Visible candle details · last 20 rows');const wrap=text(details,'div','','candle-table'),table=text(wrap,'table',''),thead=text(table,'thead',''),hr=text(thead,'tr','');
      for(const label of ['Time (IST)','Open','High','Low','Close','Volume'])text(hr,'th',label);
      const tbody=text(table,'tbody','');for(const b of model.candles.slice(-20).reverse()){const tr=text(tbody,'tr','');text(tr,'td',time(b.timestamp));for(const k of ['open','high','low','close','volume'])text(tr,'td',number(b[k]));}
      const download=text(card,'button','Download all candles (CSV)','text-button');download.type='button';download.onclick=()=>{
        const rows=['timestamp,open,high,low,close,volume',...(stock.bars||[]).map(b=>[new Date(b.timestamp).toISOString(),b.open,b.high,b.low,b.close,b.volume].join(','))];
        const url=URL.createObjectURL(new Blob([rows.join('\r\n')],{type:'text/csv'})),a=doc.createElement('a');a.href=url;a.download=stock.symbol+'-5m-candles.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
      };
    }
    if(focusedSymbol)Array.from(host.querySelectorAll?.('svg[data-chart-symbol]')||[]).find(node=>node.dataset.chartSymbol===focusedSymbol)?.focus({preventScroll:true});
  }
  const api={candleModel,signal,render,renderDepth,COLORS};
  if(typeof module==='object'&&module.exports){module.exports=api;return;}
  root.ShortlistCharts={render};
  const host=document.getElementById('shortlist-charts');if(!host)return;
  let active=false,busy=false,generation=0,connected=false,last=null,lastKey='',positions=[];const views=new Map(),interaction={dragging:false};
  async function refresh(){
    if(!active||!connected||busy||document.hidden||interaction.dragging)return;busy=true;const version=generation;
    try{
      const data=await root.WorkspaceAPI.platform('/auto/shortlist');if(version!==generation)return;data.positions=positions;
      const key=JSON.stringify([data.updatedAt,data.state,data.processing,data.automationState,positions.map(p=>[p.intentId,p.state,p.remainingQuantity,p.exitTime,p.incident]),Math.floor(Date.now()/15000)]);
      last=data;if(interaction.dragging)return;if(key!==lastKey){const open=new Set([...host.querySelectorAll('details[open]')].map(d=>d.dataset.symbol));lastKey=key;render(host,data,{limit:Number(document.getElementById('shortlist-range').value)||0,views,interaction});for(const d of host.querySelectorAll('details'))if(open.has(d.dataset.symbol))d.open=true;}
    }catch(error){if(version===generation){lastKey='';if(interaction.dragging)return;host.replaceChildren();const p=document.createElement('p');p.textContent='Candle view unavailable: '+error.message;p.className='info-note';host.append(p);}}
    finally{busy=false;}
  }
  root.ShortlistCharts={render,enter(){active=true;generation++;refresh();},leave(){interaction.cancel?.();active=false;generation++;}};
  root.AutoPaperUI.subscribeConnection(value=>{connected=value.state==='connected';if(!connected){interaction.cancel?.();generation++;last=null;lastKey='';host.textContent='Connect the workspace to view shortlisted stock candles.';}else refresh();});
  root.AutoPaperUI.subscribe(value=>{positions=value.portfolio?.positions||[];refresh();});
  document.getElementById('shortlist-refresh').onclick=refresh;
  document.getElementById('shortlist-range').onchange=()=>{lastKey='';for(const [symbol,view]of views)views.set(symbol,{...(view.day?{day:view.day}:{}),frame:view.frame});if(last)render(host,last,{limit:Number(document.getElementById('shortlist-range').value)||0,views,interaction});};
  setInterval(refresh,5000);
})(globalThis);
