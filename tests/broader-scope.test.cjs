const {test}=require('node:test'),assert=require('node:assert/strict');
const analysis=require('../backend/src/main/resources/static/analysis-view.js');
const activity=require('../backend/src/main/resources/static/automation-activity.js');
test('comparison includes both stock groups while keeping NIFTY 50 as market context',()=>{
 const coverage={nifty50:50,otherStocks:2,otherUniverseSize:450};
 const view=analysis.model({selection:{status:'NO_TRADE',observedAt:'2026-11-09T05:00:00Z',candidateSymbols:['BASE','OTHER'],scanCoverage:coverage,marketContextCoverage:50,
   candidates:[{symbol:'BASE',universeScope:'NIFTY50',score:80},{symbol:'OTHER',universeScope:'OTHER_STOCKS',score:85}]}});
 assert.equal(view.candidateCount,2);assert.equal(view.candidates[0].symbol,'OTHER');assert.deepEqual(view.scanCoverage,coverage);assert.equal(view.contextCoverage,50);
});
test('dedicated group status is visible only while the workspace has current status',()=>{
 const workers={nifty50:{status:'LOADING',completedHistories:10,totalHistories:51},otherStocks:{status:'PRICE_SCREENING',completedHistories:0,totalHistories:0}};
 const value={enabled:true,busy:true,activity:{phase:'VERIFYING_DATA',status:'PROCESSING',processing:true,universeWorkers:workers}};
 assert.deepEqual(activity.model(value).universeWorkers,workers);
 assert.equal(activity.model(value,{state:'error'}).universeWorkers,null);
});
