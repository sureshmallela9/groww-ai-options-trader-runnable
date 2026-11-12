(function(root){
  'use strict';
  const $=id=>document.getElementById(id);let sequence=0,loaded=false,items=[],currentId=null;
  function text(parent,tag,value,cls){const node=document.createElement(tag);node.textContent=value;if(cls)node.className=cls;parent.append(node);return node;}
  function displayTime(value){const date=new Date(value);return Number.isFinite(date.getTime())?date.toLocaleString('en-IN',{timeZone:'Asia/Kolkata'})+' IST':'Time not recorded';}
  function renderList(){
    const query=$('reports-search').value.trim().toLowerCase(),container=$('workspace-report-list');container.replaceChildren();
    const shown=items.filter(row=>[row.symbol,row.prompt,row.trend,row.id].join(' ').toLowerCase().includes(query));
    $('reports-count').textContent=shown.length+' of '+items.length+' loaded reports';
    if(!shown.length){text(container,'p',query?'No saved reports match your search.':'No saved reports of this type yet. Run analysis or historical replay to create one.','muted');return;}
    for(const row of shown){const id=String(row.report_id??row.id);if(!/^[1-9]\d{0,14}$/.test(id))continue;
      const card=text(container,'article','','report-row');const link=text(card,'a',(row.symbol||'Market analysis')+' · Report '+id,'report-link');link.href=root.TradingRoutes.pathFor('reports',id);link.dataset.screenTarget='reports';link.dataset.routeId=id;
      text(card,'small',displayTime(row.created_at)+' · '+(row.interval||'Interval not recorded')+' · '+(row.kind==='replay'?'Replay · '+(row.response?.metrics?.trades??0)+' trades':row.trend||'Analysis'),'muted');
      text(card,'p',row.prompt||row.analysis_prompt||'No question was recorded.','report-prompt');
    }
  }
  async function list(force=false){
    if(loaded&&!force)return;const version=++sequence;const container=$('workspace-report-list');container.textContent='Loading saved reports…';container.setAttribute('aria-busy','true');
    try{const value=await root.WorkspaceAPI.request('/api/ai/reports?kind='+encodeURIComponent($('reports-kind').value));if(version!==sequence)return;
      const rows=Array.isArray(value)?value:(value.reports||value.items||value.data);if(!Array.isArray(rows))throw Error('The service did not return a report list.');
      items=rows;loaded=true;renderList();$('reports-message').textContent=Number(value.total)>items.length?'Showing the latest '+items.length+' of '+value.total+' reports.':'Saved reports from Trading AI.';
    }catch(error){if(version!==sequence)return;container.textContent='Reports unavailable. '+error.message;$('reports-message').textContent='Use Refresh reports to try again.';}
    finally{if(version===sequence)container.removeAttribute('aria-busy');}
  }
  async function detail(id){
    currentId=id;const version=++sequence;const container=$('workspace-report-detail');$('workspace-report-index').hidden=true;container.hidden=false;container.replaceChildren();
    const back=text(container,'a','← All reports','text-button');back.href='/workspace/reports';back.dataset.screenTarget='reports';
    const body=text(container,'div','Loading report…');container.setAttribute('aria-busy','true');
    try{const report=await root.WorkspaceAPI.request('/api/ai/reports/'+encodeURIComponent(id));if(version!==sequence)return;body.replaceChildren();
      text(body,'h2',(report.symbol||report.response?.symbol||'Analysis')+' · Report '+id);
      text(body,'p',displayTime(report.created_at),'muted');text(body,'h3','Your question');text(body,'p',report.prompt||report.response?.analysis_prompt||'No question recorded.');
      const evidence=text(body,'div','');if(report.kind==='replay'||report.response?.report_type==='replay')root.AnalysisReport.renderReplay(evidence,{...report.response,report_id:id});else root.renderTradingReport(report,evidence);
      const raw=text(body,'details','','advanced');text(raw,'summary','Full saved record');text(raw,'pre',JSON.stringify(report,null,2));
    }catch(error){if(version===sequence)body.textContent='Could not load this report. '+error.message;}
    finally{if(version===sequence)container.removeAttribute('aria-busy');}
  }
  root.WorkspaceReports={enter(route){
    sequence++;root.DailyOrderReports.leave();root.ProfitLossReports.leave();
    if(route.id){$('pnl-report').hidden=true;$('daily-orders').hidden=true;if(['orders','pnl'].includes($('reports-kind').value)){$('reports-kind').value='analysis';loaded=false;}$('reports-message').hidden=false;return detail(route.id);}
    currentId=null;const daily=$('reports-kind').value==='orders',pnl=$('reports-kind').value==='pnl';
    $('pnl-report').hidden=!pnl;$('daily-orders').hidden=!daily;$('workspace-report-index').hidden=daily||pnl;$('reports-message').hidden=daily||pnl;$('workspace-report-detail').hidden=true;
    return pnl?root.ProfitLossReports.enter():daily?root.DailyOrderReports.enter():list();
  },leave(){sequence++;root.DailyOrderReports.leave();root.ProfitLossReports.leave();},invalidate(){loaded=false;root.DailyOrderReports.invalidate();root.ProfitLossReports.invalidate();}};
  $('reports-search').addEventListener('input',renderList);$('reports-kind').onchange=()=>{loaded=false;root.WorkspaceShell.navigate('reports');};
  $('reports-refresh').onclick=async()=>{const button=$('reports-refresh');button.disabled=true;try{if(currentId)await detail(currentId);else if($('reports-kind').value==='pnl')await root.ProfitLossReports.enter(true);else if(['orders','pnl'].includes($('reports-kind').value))await root.DailyOrderReports.enter(true);else await list(true);}finally{button.disabled=false;}};
})(globalThis);
