const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs"), vm = require("node:vm"), path = require("node:path");
const stream = require("../backend/src/main/resources/static/trading-ai-stream.js");
test("SSE parses fragmented CRLF, JSON, multiline data and final events", () => {
  const events=[], parser=stream.createParser(event=>events.push(event));
  for(const part of ["\ufeff: ping\r","\nevent: report\r\ndata: {\"trend\":","\"bullish\"}\r","\n\r","\nevent: done\ndata: first\ndata: second"])parser.push(part);
  parser.finish();
  assert.deepEqual(events,[{event:"report",data:'{"trend":"bullish"}'},{event:"done",data:"first\nsecond"}]);
});
test("SSE decodes Unicode across byte boundaries", async()=>{
  const bytes=new TextEncoder().encode('event: report\ndata: {"summary":"₹100"}\n\n');
  let index=0,released=false;
  const body={getReader:()=>({
    async read(){return index<bytes.length?{done:false,value:bytes.slice(index,index+=1)}:{done:true}},
    releaseLock(){released=true}
  })},events=[];
  await stream.consume(body,event=>events.push(event));
  assert.equal(JSON.parse(events[0].data).summary,"₹100");assert.equal(released,true);
});
test("saved report UI reads response, report ID, excerpts and provenance",()=>{
  const html=fs.readFileSync(path.join(__dirname,"../backend/src/main/resources/static/index.html"),"utf8");
  const start=html.indexOf("function displayValue("),end=html.indexOf("async function refreshAiStatus",start);
  assert.ok(start>=0&&end>start);
  const elements={analysisBody:{innerHTML:""},aiMode:{textContent:""}};
  const context={$:id=>elements[id],esc:value=>String(value??"")};
  vm.createContext(context);vm.runInContext(html.slice(start,end),context);
  context.renderTradingReport({id:123,response:{
    symbol:"NSE-WIPRO",trend:"bullish",summary:"Saved summary",
    knowledge_context:[{title:"Trend source",excerpt:"The relevant excerpt."}],
    market_data_summary:{provider:"GROWW",instrument:"NSE-WIPRO",source_interval:"5minute",bars_analyzed:50}
  }});
  for(const value of ["Report 123","Saved summary","The relevant excerpt","GROWW"])assert.ok(elements.analysisBody.innerHTML.includes(value));
  assert.doesNotMatch(elements.analysisBody.innerHTML,/"excerpt":/);
});
