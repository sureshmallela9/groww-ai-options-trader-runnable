(function(root){
  'use strict';
  const validDay=value=>typeof value==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(value)&&Number.isFinite(Date.parse(value+'T00:00:00Z'))&&new Date(value+'T00:00:00Z').toISOString().slice(0,10)===value;
  function today(now=Date.now()){const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date(now));const d=Object.fromEntries(parts.map(p=>[p.type,p.value]));return d.year+'-'+d.month+'-'+d.day;}
  function addDays(day,count){const date=new Date(day+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+count);return date.toISOString().slice(0,10);}
  function addMonths(day,count){const date=new Date(day+'T12:00:00Z'),original=date.getUTCDate();date.setUTCDate(1);date.setUTCMonth(date.getUTCMonth()+count);const last=new Date(Date.UTC(date.getUTCFullYear(),date.getUTCMonth()+1,0)).getUTCDate();date.setUTCDate(Math.min(original,last));return date.toISOString().slice(0,10);}
  function presetRange(preset,day=today()){
    if(!validDay(day))throw Error('Choose a valid reporting date.');
    const from=({today:()=>day,'10d':()=>addDays(day,-9),'30d':()=>addDays(day,-29),'10m':()=>addDays(addMonths(day,-10),1),'1y':()=>addDays(addMonths(day,-12),1),ytd:()=>day.slice(0,4)+'-01-01'})[preset];
    if(!from)return null;return {from:from(),to:day,groupBy:['10m','1y','ytd'].includes(preset)?'MONTH':'DAY'};
  }
  function validateRange(from,to,day=today()){return !validDay(from)||!validDay(to)?'Choose valid start and end dates.':from>to?'Start date must be on or before the end date.':to>day?'The end date cannot be in the future.':null;}
  function csv(report){const rows=[['Mode','From','To','Period','Profit INR','Loss INR','Net realized INR','Charges INR','Realized trades','Missing net records','Missing charge records']];
    for(const group of report.groups||[])rows.push([report.mode,group.from,group.to,group.period,group.profit,group.loss,group.net,group.charges,group.realizedTrades,group.missingNet,group.missingCharges]);
    const cell=value=>{let s=String(value??'');if(typeof value==='string'&&/^[=+\-@\t\r]/.test(s))s="'"+s;return '"'+s.replaceAll('"','""')+'"';};return '\uFEFF'+rows.map(row=>row.map(cell).join(',')).join('\r\n');
  }
  if(typeof module==='object'&&module.exports){module.exports={today,presetRange,validateRange,csv};return;}
  const $=id=>document.getElementById(id);if(!$('pnl-report'))return;
  let active=false,connected=false,generation=0,currentMode='PAPER',last=null;
  const money=n=>typeof n==='number'&&Number.isFinite(n)?new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(n):'Not recorded';
  function text(parent,tag,value,cls){const el=document.createElement(tag);el.textContent=String(value??'');if(cls)el.className=cls;parent.append(el);return el;}
  function clear(){last=null;for(const id of ['pnl-profit','pnl-loss','pnl-net','pnl-charges'])$(id).textContent='—';for(const id of ['pnl-groups','pnl-trades'])$(id).replaceChildren();$('pnl-coverage').textContent='';$('pnl-current').textContent='';$('pnl-range-label').textContent='';$('pnl-export').disabled=true;}
  function applyPreset(){const range=presetRange($('pnl-preset').value);if(range){$('pnl-from').value=range.from;$('pnl-to').value=range.to;$('pnl-group').value=range.groupBy;}for(const id of ['pnl-from','pnl-to'])$(id).max=today();}
  function amount(id,value,missing){const node=$(id);node.textContent=money(value)+(missing?' · known subtotal':'');node.className=value<0?'negative':'positive';}
  function render(report){
    const totals=report.totals;amount('pnl-profit',totals.profit,totals.missingNet);amount('pnl-loss',totals.loss,totals.missingNet);$('pnl-loss').className='negative';amount('pnl-net',totals.net,totals.missingNet);amount('pnl-charges',totals.charges,totals.missingCharges);
    $('pnl-range-label').textContent=report.mode+' · '+report.from+' → '+report.to+' · '+report.groupBy.toLowerCase()+' groups';
    const coverage=report.recordCoverage||{};$('pnl-coverage').textContent='Saved managed trades only. '+(coverage.firstActivity?'Recorded activity: '+coverage.firstActivity+' → '+coverage.lastActivity+'. ':'No saved activity in this ledger. ')+'Earlier account activity is not imported from Groww. '+(totals.estimatedCharges?'Charges and net P&L include estimates. ':'')+(!report.complete?'Incomplete records: '+(totals.missingNet||0)+' missing net results, '+(totals.missingCharges||0)+' missing charges; '+(report.undatedRecords||0)+' undated results.':'');
    const open=report.currentOpen||{};$('pnl-current').textContent=open.available?'Current open P&L: '+money(open.pnl)+' across '+open.positions+' position(s), as of '+report.asOf+'. This is separate from realized totals.':report.to===today()?'Current open marks are unavailable or stale; they are excluded from realized totals.':'Historical open P&L is unavailable; this report uses recorded realized exits.';
    const host=$('pnl-groups');host.replaceChildren();const table=text(host,'table',''),head=text(text(table,'thead',''),'tr','');
    for(const label of ['Period (IST)','Realized profit','Realized loss','Net realized P&L','Charges','Trades with exits'])text(head,'th',label);
    const body=text(table,'tbody',''),max=Math.max(1,...report.groups.map(g=>Math.abs(g.net||0)));
    for(const group of report.groups){const row=text(body,'tr','');text(row,'td',group.from===group.to?group.period:group.period+' · '+group.from+' → '+group.to);text(row,'td',money(group.profit),'positive');text(row,'td',money(group.loss),'negative');const net=text(row,'td',money(group.net)+(group.missingNet?' (incomplete)':''),group.net<0?'negative':'positive');
      if(group.net){const bar=text(net,'span','','pnl-bar'+(group.net<0?' loss':''));bar.style.width=(120*Math.abs(group.net)/max)+'px';bar.setAttribute('aria-hidden','true');}text(row,'td',money(group.charges)+(group.missingCharges?' (incomplete)':''));text(row,'td',group.realizedTrades);}
    if(!totals.realizedEvents)text(host,'p','No recorded realized exits in this date range. Empty periods show ₹0 for saved results.','muted');
    const details=$('pnl-trades');details.replaceChildren();const box=text(details,'details','');text(box,'summary','Matching trades · '+report.trades.length);let drawn=false;
    box.ontoggle=()=>{if(!box.open||drawn)return;drawn=true;const wrap=text(box,'div','','tablewrap'),list=text(wrap,'table',''),header=text(text(list,'thead',''),'tr','');for(const label of ['Stock / side','Entry date','Exit / status','Net in range','Charges in range'])text(header,'th',label);const rows=text(list,'tbody','');let shown=0;const more=text(box,'button','Show next 100 trades','text-button');more.type='button';const page=()=>{for(const trade of report.trades.slice(shown,shown+100)){const r=text(rows,'tr','');text(r,'td',(trade.symbol||'Unknown')+' · '+(trade.positionSide||'LONG'));text(r,'td',trade.entryTime||'Not recorded');text(r,'td',trade.exitTime||trade.state);text(r,'td',money(trade.range.net)+(trade.range.missingNet?' (incomplete)':''));text(r,'td',money(trade.range.charges));}shown+=100;more.hidden=shown>=report.trades.length;};more.onclick=page;page();};
  }
  async function load(){
    const version=++generation;$('pnl-export').disabled=true;
    if(!connected){clear();$('pnl-message').textContent='Connect the workspace to load saved results.';return;}
    const from=$('pnl-from').value,to=$('pnl-to').value,error=validateRange(from,to);if(error){$('pnl-message').textContent=error;return;}
    const filters={from,to,groupBy:$('pnl-group').value,mode:$('pnl-mode').value==='CURRENT'?currentMode:$('pnl-mode').value};
    $('pnl-report').setAttribute('aria-busy','true');$('pnl-message').textContent='Loading saved '+filters.mode.toLowerCase()+' results…';
    try{const report=await root.WorkspaceAPI.platform('/reports/profit-loss?'+new URLSearchParams(filters));if(version!==generation||!active)return;
      if(!Array.isArray(report.groups)||!Array.isArray(report.trades)||!report.totals||!['PAPER','REAL'].includes(report.mode))throw Error('The report response is incomplete.');
      last=report;render(report);$('pnl-message').textContent='Snapshot generated '+report.asOf+' · '+report.totals.realizedTrades+' trade(s) with realized exits.';$('pnl-export').disabled=false;
    }catch(error){if(version===generation){$('pnl-message').textContent='Report unavailable: '+error.message+(last?' The last displayed snapshot may be stale.':'');}}
    finally{if(version===generation)$('pnl-report').removeAttribute('aria-busy');}
  }
  $('pnl-preset').onchange=()=>{applyPreset();if(active)load();};
  for(const id of ['pnl-from','pnl-to'])$(id).onchange=()=>{generation++;$('pnl-report').removeAttribute('aria-busy');$('pnl-preset').value='custom';$('pnl-export').disabled=true;$('pnl-message').textContent='Dates changed. Choose Show report to update results.';};
  for(const id of ['pnl-group','pnl-mode'])$(id).onchange=()=>{if(active)load();};$('pnl-apply').onclick=load;
  $('pnl-export').onclick=()=>{if(!last)return;const url=URL.createObjectURL(new Blob([csv(last)],{type:'text/csv;charset=utf-8'})),a=document.createElement('a');a.href=url;a.download='pnl-'+last.mode+'-'+last.from+'-to-'+last.to+'.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
  root.ProfitLossReports={enter(){active=true;if(!$('pnl-from').value)applyPreset();return load();},leave(){active=false;generation++;$('pnl-report').removeAttribute('aria-busy');},invalidate(){generation++;clear();}};
  root.AutoPaperUI?.subscribeConnection(value=>{const previous=connected;connected=value.state==='connected';if(!connected){generation++;clear();if(active)$('pnl-message').textContent='Connect the workspace to load saved results.';}else if(active&&!previous)load();});
  root.AutoPaperUI?.subscribe(value=>{const mode=value.automation?.mode||value.portfolio?.mode;if(['PAPER','REAL'].includes(mode)&&mode!==currentMode){currentMode=mode;if(active&&$('pnl-mode').value==='CURRENT')load();}});
})(globalThis);
