const {test}=require('node:test'),assert=require('node:assert/strict');
const {intradayLabel,model}=require('../backend/src/main/resources/static/analysis-view.js');
const {model:activity}=require('../backend/src/main/resources/static/automation-activity.js');
test('candidate intraday labels distinguish checked, pending, rejected and unavailable',()=>{
 for(const [status,label] of Object.entries({PASSED:'MIS preflight passed',PENDING:'Intraday check pending',REJECTED:'Intraday rejected',UNAVAILABLE:'Intraday check unavailable'}))assert.equal(intradayLabel({intradayCheck:{status}}),label);
 assert.equal(intradayLabel({}),'Not checked');
 const value=model({selection:{status:'NO_TRADE',observedAt:'2026-11-12T05:00:00Z',candidateSymbols:['ITC'],candidates:[{symbol:'ITC',candidateStatus:'PENDING_INTRADAY_CHECK',intradayCheck:{status:'PENDING'}}]}});
 assert.equal(value.candidates[0].intradayCheck.status,'PENDING');assert.equal(value.action,'NO_TRADE');
});
test('MIS preflight is shown as active analysis while processing',()=>{
 const value=activity({enabled:true,busy:true,state:'INTRADAY_CHECK',activity:{status:'PROCESSING',phase:'INTRADAY_CHECK',processing:true}});
 assert.match(value.title,/intraday \/ MIS/);assert.equal(value.processing,true);
});
