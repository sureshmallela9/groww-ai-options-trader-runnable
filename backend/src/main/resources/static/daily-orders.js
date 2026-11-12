(function(root){
  'use strict';
  const dayFormatter=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'});
  function dayOf(value){
    if(typeof value!=='string'||!value.trim())return null;
    const date=new Date(value);if(!Number.isFinite(date.getTime()))return null;
    const parts=Object.fromEntries(dayFormatter.formatToParts(date).map(part=>[part.type,part.value]));
    return parts.year+'-'+parts.month+'-'+parts.day;
  }
  function validDay(value){return typeof value==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(value)&&dayOf(value+'T00:00:00+05:30')===value;}
  const numeric=value=>typeof value==='number'&&Number.isFinite(value)?value:null;
  const cents=value=>numeric(value)===null?null:Math.round(value*100);
  function describe(row){
    const entryDay=numeric(row.filledQuantity)>0&&numeric(row.entryPrice)>0?dayOf(row.entryTime):null;
    const exitDay=entryDay&&row.state==='POSITION_CLOSED'&&row.remainingQuantity===0&&numeric(row.exitPrice)>0?dayOf(row.exitTime):null;
    const requestDay=dayOf(row.createdAt)||(validDay(row.sessionDate)?row.sessionDate:null);
    const pendingDays=(Array.isArray(row.timeline)?row.timeline:[]).filter(event=>event.state==='EXIT_PENDING').map(event=>dayOf(event.at)).filter(Boolean);
    const days=[...new Set([requestDay,entryDay,exitDay,...pendingDays,...Object.keys(row.realizedByDay||{})].filter(Boolean))];
    return {row,entryDay,exitDay,requestDay,pendingDays,days};
  }
  function buildDailyReport(records,selectedDay){
    const described=records.map(describe);
    const dates=[...new Set(described.flatMap(item=>item.days))].sort().reverse();
    const rows=described.filter(item=>item.days.includes(selectedDay)).sort((a,b)=>String(b.row.entryTime||b.row.createdAt||'').localeCompare(String(a.row.entryTime||a.row.createdAt||'')));
    const exits=rows.filter(item=>item.exitDay===selectedDay),entries=rows.filter(item=>item.entryDay===selectedDay);
    const realized=rows.filter(item=>item.row.mode==='REAL'?numeric(item.row.realizedByDay?.[selectedDay]?.net)!==null:item.exitDay===selectedDay);
    const known=realized.map(item=>cents(item.row.mode==='REAL'?item.row.realizedByDay[selectedDay].net:item.row.realizedPnl)).filter(value=>value!==null);
    return {dates,rows,entries:entries.length,exits:exits.length,
      pending:rows.filter(item=>item.row.state==='EXIT_PENDING').length,
      realizedPnl:known.reduce((sum,value)=>sum+value,0)/100,
      charges:realized.reduce((sum,item)=>sum+(cents(item.row.mode==='REAL'?item.row.realizedByDay[selectedDay].charges:item.row.charges)??0),0)/100,
      missingPnl:exits.filter(item=>item.row.mode==='REAL'?numeric(item.row.realizedByDay?.[selectedDay]?.net)===null:numeric(item.row.realizedPnl)===null).length,
      missingCharges:exits.filter(item=>cents(item.row.charges)===null).length,
      undated:described.filter(item=>!item.days.length).length};
  }
  if(typeof module==='object'&&module.exports){module.exports={dayOf,validDay,describe,buildDailyReport};return;}
  const $=id=>document.getElementById(id);
  let records=[],loaded=false,active=false,generation=0,signature='',currentMode=null;
  const money=value=>numeric(value)===null?'Not recorded':new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(value);
  const time=value=>dayOf(value)?new Date(value).toLocaleString('en-IN',{timeZone:'Asia/Kolkata',day:'2-digit',month:'short',year:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false})+' IST':'Not recorded';
  const human=value=>String(value||'Not recorded').replaceAll('_',' ');
  function text(parent,tag,value,cls){const node=document.createElement(tag);node.textContent=value;if(cls)node.className=cls;parent.append(node);return node;}
  function pnl(parent,value){return text(parent,'strong',money(value),numeric(value)===null?'muted':value<0?'negative':'positive');}
  function setRecords(rows){
    records=rows;loaded=true;
    if(!$('orders-day').value){const dates=buildDailyReport(records,'').dates;$('orders-day').value=dates[0]||dayOf(new Date().toISOString());}
    if(active)render();
  }
  function details(parent,item,day){
    const row=item.row,box=text(parent,'details','','order-details');text(box,'summary','Order details');
    const facts=text(box,'dl','','order-facts');
    for(const [label,value] of [['Order / intent ID',row.intentId||'Not recorded'],['Decision ID',row.decisionId||'Not recorded'],['Requested',time(row.createdAt)],['Entry fill',time(row.entryTime)],['Exit fill',item.exitDay?time(row.exitTime):'No completed exit recorded'],['Current status',human(row.state)],['Remaining quantity',row.remainingQuantity??'Not recorded'],['Exit reason',human(row.exitReason)],['Gross realized P&L',item.exitDay?money(row.grossPnl):'No completed exit'],['Charges (estimated unless broker-finalized)',item.exitDay?money(row.charges):'Not finalized'],['Total net realized P&L',item.exitDay?money(row.realizedPnl):'No completed exit'],['Incident / rejection',row.incident||row.riskRejections?.join('; ')||row.rejectionReasons?.join('; ')||row.errors?.join('; ')||'None recorded']]){
      text(facts,'dt',label);text(facts,'dd',String(value));
    }
    if(item.exitDay&&item.exitDay!==day)text(box,'p','This trade closed on '+item.exitDay+'. Its realized P&L belongs to that exit day.','hint');
    text(box,'p','Status is the latest saved state. Historical days show recorded activity, not a reconstructed end-of-day position balance.','hint');
    if(/^[A-Za-z0-9_-]{1,100}$/.test(row.intentId||'')){const link=text(box,'a','View position →','text-button');link.href='/workspace/positions/'+encodeURIComponent(row.intentId);link.dataset.screenTarget='positions';link.dataset.routeId=row.intentId;}
  }
  function render(){
    const day=$('orders-day').value,query=$('orders-search').value.trim().toLowerCase();
    const report=buildDailyReport(records,day),container=$('daily-order-list');container.replaceChildren();
    $('orders-entries').textContent=String(report.entries);$('orders-exits').textContent=String(report.exits);
    $('orders-pending').textContent=String(report.pending);
    const total=$('orders-realized');total.textContent=money(report.realizedPnl)+(report.missingPnl?' (incomplete)':'');total.className=report.realizedPnl<0?'negative':'positive';
    $('orders-summary-note').textContent='Realized P&L follows recorded exits, including partial real fills. Charges remain estimates until broker-finalized. '+(report.exits?'Charges on this day: '+money(report.charges)+(report.missingCharges?' (incomplete records).':'.'):'No completed exits on this day.')+(report.undated?' '+report.undated+' undated record(s) could not be assigned to a day.':'');
    const dates=$('orders-recorded-days');dates.replaceChildren();
    const placeholder=text(dates,'option','Choose a day with recorded activity');placeholder.value='';
    for(const value of report.dates){const option=text(dates,'option',value);option.value=value;}dates.value=report.dates.includes(day)?day:'';
    const shown=report.rows.filter(item=>[item.row.symbol,item.row.intentId,item.row.state,item.row.positionSide].join(' ').toLowerCase().includes(query));
    $('orders-count').textContent=shown.length+' of '+report.rows.length+' orders with recorded activity on '+day+' · All times IST';
    if(!shown.length){text(container,'p',query?'No orders match this search on the selected day.':'No order activity recorded on this day. Choose another date or a day with recorded activity.','muted');return;}
    const wrap=text(container,'div','','tablewrap'),table=text(wrap,'table','','daily-orders-table');
    const header=text(text(table,'thead',''),'tr','');for(const label of ['Stock / direction','Quantity','Entry fill','Exit fill / current status','Stop / target','Net realized on this day','Details']){const th=text(header,'th',label);th.scope='col';}
    const body=text(table,'tbody','');
    for(const item of shown){
      const row=item.row,tr=text(body,'tr',''),stock=text(tr,'td','');text(stock,'strong',row.symbol||'Stock not recorded');text(stock,'small',(row.positionSide==='SHORT'?'SELL / SHORT':'BUY / LONG')+' · '+(row.mode||'PAPER'),'muted');
      text(tr,'td',String(row.filledQuantity??0)+' filled / '+String(row.quantity??'—')+' requested');
      const entry=text(tr,'td','');text(entry,'strong',item.entryDay?money(row.entryPrice):'Not filled');text(entry,'small',item.entryDay?time(row.entryTime):'No confirmed entry','muted');
      const exit=text(tr,'td','');text(exit,'strong',item.exitDay?money(row.exitPrice):row.state==='EXIT_PENDING'?'Exit pending — no fill':'No completed exit');
      text(exit,'small',item.exitDay?time(row.exitTime):human(row.state),'muted');
      if(item.exitDay)text(exit,'small',(row.positionSide==='SHORT'?'BUY / cover':'SELL / exit')+' · '+human(row.state),'muted');
      const risk=text(tr,'td','');text(risk,'small','Stop '+money(row.stopLoss));text(risk,'small','Target '+money(row.targetPrice));
      const net=text(tr,'td','');if(row.mode==='REAL'&&row.realizedByDay?.[day])pnl(net,row.realizedByDay[day].net);else if(item.exitDay===day)pnl(net,row.realizedPnl);else text(net,'span',item.exitDay?'On '+item.exitDay:'Not realized','muted');
      details(text(tr,'td',''),item,day);
    }
  }
  async function load(force=false){
    if(loaded&&!force){render();return;}
    const version=++generation;$('daily-order-list').textContent='Loading saved orders…';$('daily-orders').setAttribute('aria-busy','true');
    try{const value=await root.WorkspaceAPI.request('/api/v1/trade-intents');if(version!==generation||!active)return;
      if(!Array.isArray(value.intents))throw Error('The service did not return an order list.');
      setRecords(value.intents);$('orders-load-message').textContent='Saved orders in selected mode · Updated '+time(new Date().toISOString());
    }catch(error){if(version===generation){$('daily-order-list').textContent='Orders unavailable. '+error.message;$('orders-load-message').textContent='Connect the workspace, then refresh reports to try again.';}}
    finally{if(version===generation)$('daily-orders').removeAttribute('aria-busy');}
  }
  $('orders-day').onchange=render;$('orders-search').oninput=render;
  $('orders-recorded-days').onchange=()=>{if($('orders-recorded-days').value){$('orders-day').value=$('orders-recorded-days').value;render();}};
  $('orders-today').onclick=()=>{$('orders-day').value=dayOf(new Date().toISOString());render();};
  root.DailyOrderReports={enter(force=false){active=true;return load(force);},leave(){active=false;generation++;$('daily-orders').removeAttribute('aria-busy');},invalidate(){loaded=false;generation++;records=[];signature='';if(active)render();}};
  root.AutoPaperUI?.subscribe(value=>{
    if(!Array.isArray(value.portfolio?.positions))return;
    const mode=value.portfolio.mode||'PAPER';if(currentMode!==mode){currentMode=mode;generation++;records=[];loaded=false;signature='';}
    // Ignore quote-only updates; these reports summarize recorded orders and realized results.
    const rows=value.portfolio.positions,key=JSON.stringify(rows.map(row=>[row.intentId,row.state,row.entryTime,row.exitTime,row.realizedPnl,row.incident]));
    if(key!==signature){signature=key;setRecords(rows);if(active)$('orders-load-message').textContent='Saved orders in selected mode · Updated '+time(value.portfolio.updatedAt);}
  });
  root.AutoPaperUI?.subscribeConnection(value=>{
    if(value.state==='connected'){if(active&&!loaded)load();}
    else if(value.state==='disconnected'){generation++;records=[];loaded=false;signature='';if(active){render();$('orders-load-message').textContent='Connect the workspace to load saved orders.';}}
    else if(value.state==='error'&&active)$('orders-load-message').textContent='Update unavailable. Last loaded order records may be stale. '+value.message;
  });
})(globalThis);
