(function(root){
  'use strict';
  function today(now=Date.now()){return new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(now));}
  function day(value){return typeof value==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(value)&&Number.isFinite(Date.parse(value+'T00:00:00Z'))&&new Date(value+'T00:00:00Z').toISOString().slice(0,10)===value;}
  function shift(value,count){const date=new Date(value+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+count);return date.toISOString().slice(0,10);}
  function range(preset,end=today()){return preset==='today'?{from:end,to:end}:preset==='yesterday'?{from:shift(end,-1),to:shift(end,-1)}:preset==='7d'?{from:shift(end,-6),to:end}:preset==='30d'?{from:shift(end,-29),to:end}:null;}
  function validate(filters,end=today()){if(!day(filters.from)||!day(filters.to)||filters.from>filters.to||filters.to>end)return 'Choose valid ordered dates ending today or earlier.';if((Date.parse(filters.to)-Date.parse(filters.from))/86400000>365)return 'Choose at most 366 days.';if(filters.symbol&&!/^[A-Z0-9&._-]{1,80}$/.test(filters.symbol))return 'Enter a valid stock symbol.';return null;}
  const labels={SUCCESS:'Groww success',HTTP_SUCCESS:'HTTP success',BROKER_FAILURE:'Groww failure',HTTP_ERROR:'HTTP error',TRANSPORT_ERROR:'Connection error'};
  function resultLabel(row){return (labels[row.outcome]||'Unknown result')+(row.httpStatus?' · '+row.httpStatus:'')+(row.brokerCode?' · '+row.brokerCode:'');}
  if(typeof module==='object'&&module.exports){module.exports={today,range,validate,resultLabel};return;}
  const $=id=>document.getElementById(id);if(!$('groww-api-audit'))return;
  let active=false,connected=false,loading=false,generation=0,dirty=false,last=null,filters=null,cursor=null,history=[];
  function text(parent,tag,value,cls){const node=document.createElement(tag);node.textContent=String(value??'');if(cls)node.className=cls;parent.append(node);return node;}
  function clear(){last=null;filters=null;cursor=null;history=[];for(const id of ['audit-calls','audit-success','audit-failed','audit-rate','audit-latency'])$(id).textContent='—';$('audit-table').replaceChildren();$('audit-coverage').textContent='';$('audit-page').textContent='';for(const id of ['audit-export','audit-prev','audit-next'])$(id).disabled=true;}
  function preset(){const value=range($('audit-preset').value);if(value){$('audit-from').value=value.from;$('audit-to').value=value.to;}for(const id of ['audit-from','audit-to'])$(id).max=today();}
  function readFilters(){return {from:$('audit-from').value,to:$('audit-to').value,category:$('audit-category').value||'ALL',outcome:$('audit-outcome').value||'ALL',symbol:$('audit-symbol').value.trim().toUpperCase()};}
  function render(report){
    for(const [id,key]of [['audit-calls','calls'],['audit-success','successful'],['audit-failed','failed'],['audit-rate','rateLimited']])$(id).textContent=String(report.totals[key]);
    $('audit-latency').textContent=Number(report.totals.averageMs).toFixed(1)+' ms';
    const coverage=report.coverage||{};
    $('audit-coverage').textContent=(coverage.recordingStartedAt?'Recording began '+new Date(coverage.recordingStartedAt).toLocaleString('en-IN',{timeZone:'Asia/Kolkata'})+' IST. ':'')+'Earlier calls were not recorded in this audit. '+(!report.complete?'Audit incomplete: '+(coverage.unrecordedCallsSinceStart||0)+' calls could not be saved since startup; '+(report.malformedRecords||0)+' unreadable records. '+(coverage.lastStorageErrorType||''):'')+' HTTP success is a network result, not proof that an order filled.';
    const host=$('audit-table');host.replaceChildren();
    if(!report.records.length){text(host,'p','No recorded Groww network calls match these filters. Recording begins when the updated app makes its next call.','muted');return;}
    const table=text(host,'table',''),head=text(text(table,'thead',''),'tr','');for(const name of ['Time (IST)','Purpose / stock','Method / endpoint','Result','Duration','Details'])text(head,'th',name);
    const body=text(table,'tbody','');for(const row of report.records){
      const tr=text(body,'tr','');text(tr,'td',new Date(row.startedAt).toLocaleString('en-IN',{timeZone:'Asia/Kolkata'}));
      const purpose=text(tr,'td',row.purpose);const stock=row.request?.trading_symbol||row.request?.groww_symbol;if(stock)text(purpose,'div',stock,'hint');
      text(tr,'td',row.method+' '+row.endpoint,'audit-endpoint');text(tr,'td',resultLabel(row),['BROKER_FAILURE','HTTP_ERROR','TRANSPORT_ERROR'].includes(row.outcome)?'audit-failure':'');text(tr,'td',row.durationMs+' ms');
      const details=text(text(tr,'td',''),'details','');text(details,'summary','View');text(details,'pre',JSON.stringify({callId:row.id,correlationId:row.correlationId,category:row.category,request:row.request,brokerStatus:row.brokerStatus,brokerCode:row.brokerCode,retryAfterSeconds:row.retryAfterSeconds,errorType:row.errorType,completedAt:row.completedAt},null,2),'audit-details');
    }
  }
  async function load(next=null,move='replace'){
    if(!active||!connected||loading)return;
    const selected=move==='replace'?readFilters():filters,error=validate(selected);if(error){$('audit-message').textContent=error;return;}
    const version=++generation;loading=true;$('groww-api-audit').setAttribute('aria-busy','true');for(const id of ['audit-export','audit-prev','audit-next'])$(id).disabled=true;
    $('audit-message').textContent='Loading saved Groww calls…';const query={...selected,limit:200};if(next)query.cursor=next;if(move!=='replace'&&last?.snapshotAt)query.snapshotAt=last.snapshotAt;
    try{const report=await root.WorkspaceAPI.platform('/reports/groww-api?'+new URLSearchParams(query));if(version!==generation||!active||!connected)return;
      if(!report.totals||!Array.isArray(report.records))throw Error('The audit response is incomplete.');
      if(move==='next')history.push(cursor);else if(move==='prev')history.pop();else history=[];
      cursor=next;filters=selected;dirty=false;last=report;render(report);
      $('audit-message').textContent='Snapshot '+new Date(report.snapshotAt).toLocaleString('en-IN',{timeZone:'Asia/Kolkata'})+' IST. Refresh reads saved records only.';
      $('audit-page').textContent='Page '+(history.length+1)+' · '+report.records.length+' shown · '+report.totals.calls+' matching calls';
      $('audit-prev').disabled=history.length===0;$('audit-next').disabled=!report.hasMore;$('audit-export').disabled=!report.complete;
    }catch(error){if(version===generation){$('audit-message').textContent='Audit unavailable: '+error.message+(last?' The previous snapshot may be stale.':'');if(error.status===401)connected=false;}}
    finally{if(version===generation){loading=false;$('groww-api-audit').removeAttribute('aria-busy');}}
  }
  function changed(){dirty=true;generation++;loading=false;$('groww-api-audit').removeAttribute('aria-busy');for(const id of ['audit-export','audit-prev','audit-next'])$(id).disabled=true;$('audit-message').textContent='Filters changed. Choose Show audit to update the report.';}
  $('audit-filters').onsubmit=event=>{event.preventDefault();load();};
  $('audit-preset').onchange=()=>{preset();changed();};
  for(const id of ['audit-from','audit-to'])$(id).onchange=()=>{$('audit-preset').value='custom';changed();};
  for(const id of ['audit-category','audit-outcome','audit-symbol'])$(id).oninput=changed;
  $('audit-next').onclick=()=>{if(last?.nextCursor)load(last.nextCursor,'next');};$('audit-prev').onclick=()=>{if(history.length)load(history.at(-1),'prev');};
  $('audit-export').onclick=async()=>{
    if(!last||!filters||dirty)return;const version=generation;$('audit-export').disabled=true;
    try{const report=await root.WorkspaceAPI.platform('/reports/groww-api/export?'+new URLSearchParams({...filters,snapshotAt:last.snapshotAt}));if(version!==generation||!active||!connected)return;
      if(typeof report.csv!=='string'||typeof report.fileName!=='string')throw Error('The export response is incomplete.');
      const url=URL.createObjectURL(new Blob([report.csv],{type:'text/csv;charset=utf-8'})),link=document.createElement('a');link.href=url;link.download=report.fileName;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
      $('audit-message').textContent='Exported '+report.rowCount+' matching calls from the displayed snapshot.';
    }catch(error){if(version===generation)$('audit-message').textContent='Export unavailable: '+error.message;}finally{if(version===generation)$('audit-export').disabled=false;}
  };
  root.GrowwApiAudit={enter(){active=true;if(!$('audit-from').value)preset();return load();},leave(){active=false;generation++;loading=false;$('groww-api-audit').removeAttribute('aria-busy');}};
  root.AutoPaperUI?.subscribeConnection(value=>{const prior=connected;connected=value.state==='connected';if(!connected){generation++;loading=false;clear();$('audit-message').textContent='Connect the workspace to load saved API calls.';}else if(active&&!prior)load();});
  setInterval(()=>{if(active&&connected&&!document.hidden&&$('audit-auto').checked&&!cursor&&!dirty&&!loading)load();},10000);
})(globalThis);
