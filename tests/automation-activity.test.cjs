const {test}=require('node:test'),assert=require('node:assert/strict');
const activity=require('../backend/src/main/resources/static/automation-activity.js');
const connected={state:'connected',updatedAt:'2026-10-30T04:13:05Z'};
const working={enabled:true,busy:true,state:'VERIFYING_DATA',activity:{status:'PROCESSING',processing:true,phase:'VERIFYING_DATA',serverTime:'2026-10-30T04:13:05Z',phaseStartedAt:'2026-10-30T04:13:00Z',completedHistories:11,totalHistories:51,currentSymbol:'TEST10',lastCheckedAt:'2026-10-30T04:13:00Z'}};

test('only actual active work shows processing and counts represent validated histories',()=>{
  const view=activity.model(working,connected);
  assert.equal(view.processing,true);assert.equal(view.title,'Validating market data');
  assert.deepEqual(view.progress,{completed:11,total:51,symbol:'TEST10'});assert.equal(view.elapsed,5);
  assert.equal(activity.model({...working,busy:false},connected).processing,false);
  assert.equal(activity.model({...working,busy:false},connected).kind,'WAITING');
});
test('event restrictions override busy and never animate a blocked entry',()=>{
  const view=activity.model({...working,scheduledEventRisk:true},connected);
  assert.equal(view.kind,'BLOCKED');assert.equal(view.processing,false);
  assert.equal(view.title,'Manual event pause');assert.match(view.message,/manual scheduled-event pause/);assert.match(view.message,/Review the manual pause/);
  const stopped=activity.model({enabled:false,busy:false,state:'STOPPED',scheduledEventRisk:true},connected);
  assert.equal(stopped.title,'Automation stopped');assert.equal(stopped.kind,'STOPPED');
});
test('status connection failures suppress processing and mark the latest state unavailable',()=>{
  const view=activity.model(working,{state:'error',message:'Request timed out'});
  assert.equal(view.processing,false);assert.equal(view.kind,'UNKNOWN');assert.match(view.message,/may be stale/);
  assert.equal(view.progress,null);assert.equal(view.receivedAt,'Not connected');assert.deepEqual(view.recent,[]);
  assert.equal(activity.model(working,{state:'disconnected'}).kind,'DISCONNECTED');
});
test('no-trade responses show results and a qualified next-candle time',()=>{
  const view=activity.model({enabled:true,busy:false,state:'NO_TRADE',message:'["No setup","Weak volume"]',activity:{status:'WAITING',phase:'NO_TRADE',lastSelectionAt:'2026-10-30T04:13:00Z',nextScanEligibleAt:'2026-10-30T04:15:00Z',lastScanSummary:'No qualifying stock.'}},connected);
  assert.equal(view.title,'Scan complete · no qualifying stock');assert.match(view.message,/No setup · Weak volume/);
  assert.match(view.next,/Next candle eligible/);assert.match(view.scanAt,/IST/);assert.equal(view.progress,null);
});
test('older servers retain useful fallback status without inventing progress',()=>{
  const view=activity.model({enabled:true,busy:false,state:'EVENT_RISK',message:'Entries blocked.'},connected);
  assert.equal(view.kind,'BLOCKED');assert.equal(view.checkedAt,'Not yet');assert.equal(view.progress,null);
  assert.equal(activity.model({enabled:false,state:'PAUSED'},connected).processing,false);
  assert.equal(activity.model({enabled:true,state:'CALENDAR_INVALID'},connected).kind,'BLOCKED');
});
test('renderer keeps source text inert and only shows a real progress meter during data loading',()=>{
  class Element{
    constructor(tag){this.tag=tag;this.children=[];this.attributes={};this._text='';}
    set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(c=>c.textContent).join(' ');}
    set innerHTML(v){throw Error('Untrusted text must not become HTML');}
    append(...children){this.children.push(...children);}replaceChildren(...children){this._text='';this.children=children;}
    setAttribute(k,v){this.attributes[k]=String(v);}
  }
  const doc={createElement:tag=>new Element(tag)},target=new Element('div');target.ownerDocument=doc;
  activity.render(target,working,{connection:connected});assert.match(target.textContent,/11 \/ 51 histories/);
  const meter=target.children.find(c=>c.tag==='progress');assert.equal(meter.value,11);assert.equal(meter.max,51);
  activity.render(target,{enabled:true,busy:false,state:'NO_TRADE',message:'<img src=x onerror=alert(1)>'},{connection:connected});
  assert.match(target.textContent,/<img src=x onerror=alert\(1\)>/);assert.equal(target.children.some(c=>c.tag==='progress'),false);
});
