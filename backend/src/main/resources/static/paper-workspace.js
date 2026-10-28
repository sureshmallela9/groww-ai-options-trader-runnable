(function(){
"use strict";
const $=id=>document.getElementById(id);let token="",market=null,decision=null,poll=null,policyDirty=false;
$("policy").addEventListener("input",()=>{policyDirty=true;});
function message(text,error=false){$("message").textContent=text;$("message").className="notice"+(error?" error":"");}
async function api(path,body,method="POST",admin=false,key=null){
  const headers={"Content-Type":"application/json","Authorization":"Bearer "+token};
  if(admin)headers["X-Admin-Token"]=$("admin").value;
  if(key)headers["Idempotency-Key"]=key;
  const response=await fetch("/api/v1"+path,{method:body===undefined?"GET":method,headers,body:body===undefined?undefined:JSON.stringify(body)});
  const data=await response.json();
  if(!response.ok)throw Error(typeof(data.error||data.detail)==="string"?(data.error||data.detail):"The request was rejected. Check the data and service status.");
  return data;
}
function on(id,action){$(id).addEventListener("click",async()=>{const button=$(id);button.disabled=true;try{await action()}catch(error){message(error.message,true)}finally{button.disabled=false;if(id==="submit")button.disabled=!decision||decision.action!=="BUY";}});}
function text(parent,tag,value){const node=document.createElement(tag);node.textContent=value;parent.append(node);return node;}
async function refresh(){
 const status=await api("/risk/status");$("connection").textContent="Connected · Paper mode · Automatic entries off";
 const risk=$("riskStatus");risk.replaceChildren();text(risk,"p","Kill switch: "+(status.killSwitch?"ACTIVE":"off"));
 text(risk,"p","Session: "+(status.calendar.verified?(status.calendar.sessionOpen?"open":"closed"):"calendar not verified"));
 text(risk,"p","Capital ₹"+status.policy.capital+" · max "+status.policy.maxOpenPositions+" open position(s) · "+status.pendingEvents+" pending events");
 if(!policyDirty&&document.activeElement!==$("policy"))$("policy").value=JSON.stringify(status.policy,null,2);
 const listing=await api("/trade-intents"),root=$("positions");root.replaceChildren();
 for(const item of listing.intents){
  const row=document.createElement("tr");root.append(row);
  text(row,"td",item.symbol);text(row,"td",item.state+(item.incident?" · "+item.incident:""));
  text(row,"td",String(item.remainingQuantity??0));
  text(row,"td","₹"+(item.state==="POSITION_CLOSED"?item.realizedPnl:item.unrealizedPnl??0));
  const actions=text(row,"td","");const view=text(actions,"button","Details");view.onclick=()=>{$("intentDetails").textContent=JSON.stringify(item,null,2);};
  if(["POSITION_OPEN","EXIT_PENDING"].includes(item.state)){
   const exit=text(actions,"button","Request exit");exit.onclick=async()=>{try{const result=await api("/paper/positions/"+encodeURIComponent(item.intentId)+"/close",{});$("intentDetails").textContent=JSON.stringify(result,null,2);await refresh();}catch(error){message(error.message,true)}};
  }
 }
}
async function universe(refreshList){
 const data=await api("/research/universe"+(refreshList?"/refresh":""),refreshList?{}:undefined);
 const select=$("symbol");select.replaceChildren();
 for(const row of data.constituents||[]){const option=document.createElement("option");option.value=row.symbol;option.textContent=row.symbol+" · "+row.companyName;select.append(option);}
 message(data.constituents?.length?"Loaded "+data.constituents.length+" maintained symbols.":"No universe snapshot is configured. Refresh the official list.");
}
function marketRequired(){if(!market)throw Error("Load the market context first.");return market;}
function showDecision(value){
 decision=value;$("decisionDetails").textContent=JSON.stringify(value,null,2);
 $("proposal").textContent=value.action+" · "+value.symbol+" · "+value.requestedQuantity+" share(s) · reference ₹"+value.referencePrice+" · stop ₹"+value.stopLoss+" · target ₹"+value.targetPrice;
 $("submit").disabled=value.action!=="BUY";$("confirm").checked=false;
 const root=$("decision");root.replaceChildren();text(root,"h3",value.action);
 for(const reason of value.reasons||[])text(root,"p",reason);
 for(const article of value.newsContext||[]){
  text(root,"p",article.headline+" · "+article.source+" · "+article.sentiment+" · "+article.verification);
  if(article.sourceUrl){try{const url=new URL(article.sourceUrl);if(["https:","http:"].includes(url.protocol)){
   const link=text(root,"a","Read source");link.href=url.href;link.target="_blank";link.rel="noopener noreferrer";
  }}catch{/* Malformed source links remain plain recorded metadata. */}}
 }
}
on("connect",async()=>{token=$("token").value.trim();$("token").value="";await refresh();message("Connected. Load market context and a current universe snapshot.");await universe(false);});
on("refresh",refresh);on("universe",()=>universe(true));on("loadUniverse",()=>universe(false));
on("market",async()=>{
 const index=await api("/market-data/candles/query",{symbol:"NIFTY",exchange:"NSE",interval:"5m",lookbackCandles:100});
 const sector=$("sector").value.trim();const sectorData=sector?await api("/market-data/candles/query",{symbol:sector,exchange:"NSE",interval:"5m",lookbackCandles:100}):null;
 market={niftyBars:index.bars,sectorBars:sectorData?.bars||[],observedAt:new Date().toISOString(),
   advancers:$("advancers").value===""?null:Number($("advancers").value),
   decliners:$("decliners").value===""?null:Number($("decliners").value),scheduledEventRisk:$("eventRisk").checked};
 const result=await api("/research/market",market);$("marketResult").textContent=result.marketRegime+" · "+result.reasons.join(" ");
 message(result.tradingAllowed?"Market checks permit research for a paper entry.":"Market checks currently block new proposals.");
});
on("evaluate",async()=>{const symbol=$("symbol").value;if(!symbol)throw Error("Choose a stock from the maintained universe.");
 showDecision(await api("/research/evaluate",{symbol,exchange:"NSE",interval:"5m",market:marketRequired(),mode:"PAPER"}));});
on("scan",async()=>{
 const job=await api("/research/scan",{market:marketRequired()});if(poll)clearInterval(poll);let attempts=0;
 $("scanResult").textContent="Scan queued. You can continue using the workspace.";const jobId=job.jobId;
 poll=setInterval(async()=>{try{
  const result=await api("/research/jobs/"+encodeURIComponent(jobId));
  if(result.status==="FAILED")throw Error(result.error||"Scan failed.");
  if(result.status==="COMPLETED"){clearInterval(poll);poll=null;$("scanResult").textContent=result.shortlist.length?"Shortlist: "+result.shortlist.map(row=>row.symbol+" ("+row.overallScore+")").join(", "):"No candidates passed the configured checks.";if(result.selected)$("symbol").value=result.selected;}
  if(++attempts>=150){clearInterval(poll);poll=null;message("Scan is taking longer than expected. Job ID: "+jobId,true);}
 }catch(error){clearInterval(poll);poll=null;message(error.message,true)}},2000);
});
on("submit",async()=>{
 if(!decision||decision.action!=="BUY"||!$("confirm").checked)throw Error("Review and confirm a BUY paper proposal first.");
 const result=await api("/trade-intents",{decisionId:decision.decisionId,mode:"PAPER",confirmed:true}, "POST",false,decision.decisionId);
 $("intentDetails").textContent=JSON.stringify(result,null,2);message("Paper intent: "+result.state);await refresh();
});
on("kill",async()=>{await api("/risk/kill-switch",{enabled:true});message("Kill switch activated. Paper exits are pending until fresh quotes are available.");await refresh();});
on("resetKill",async()=>{await api("/risk/kill-switch",{enabled:false},"POST",true);await refresh();});
on("savePolicy",async()=>{await api("/risk/policy",JSON.parse($("policy").value),"PUT",true);policyDirty=false;message("Administrator risk settings saved.");await refresh();});
on("newsAnalyze",async()=>{$("newsResult").textContent=JSON.stringify(await api("/research/news/analyze",JSON.parse($("news").value)),null,2);});
on("newsCollect",async()=>{$("newsResult").textContent=JSON.stringify(await api("/research/news/collect",{}),null,2);});
setInterval(()=>{if(token&&!document.hidden)refresh().catch(error=>message(error.message,true));},5000);
})();
