const {test}=require('node:test'),assert=require('node:assert/strict');
const {bandRows,readRules}=require('../backend/src/main/resources/static/intraday-rules.js');
test('price band labels use exclusive upper bounds and a final open range',()=>{
  assert.deepEqual(bandRows({priceBands:[{belowPrice:100,stopLossPercent:.8,targetProfitPercent:2.4},{belowPrice:500,stopLossPercent:.5,targetProfitPercent:1.8},{belowPrice:null,stopLossPercent:.4,targetProfitPercent:1.6}]}).map(r=>r.range),['₹0 to below ₹100','₹100 to below ₹500','₹500 and above']);
});
test('form values preserve edited price limits, percentage units and paper leverage',()=>{
  const values={fallbackEnabled:{checked:true},fallbackMaxPrice:{value:'450'},paperLeverage:{value:'4'},priceBandsEnabled:{checked:true}};
  const band={querySelector(s){return {'[name=belowPrice]':null,'[name=stopLossPercent]':{value:'.75'},'[name=targetProfitPercent]':{value:'2.25'}}[s];}};
  const form={querySelector(s){return values[s.slice(6,-1)];},querySelectorAll(s){return s==='[data-price-band]'?[band]:[];}};
  assert.deepEqual(readRules(form),{capitalPriceFilterEnabled:true,capitalPriceBands:require('../backend/src/main/resources/static/intraday-rules.js').defaultCapitalBands(),fallbackEnabled:true,fallbackMaxPrice:450,paperLeverage:4,realMaxLeverage:1,priceBandsEnabled:true,priceBands:[{belowPrice:null,stopLossPercent:.75,targetProfitPercent:2.25}]});
});

test('readable analysis identifies price fallback and exposes actual sizing assumptions',()=>{
  const {model}=require('../backend/src/main/resources/static/analysis-view.js');
  const view=model({selection:{status:'CANDIDATE_SELECTED',selectionMode:'PRICE_FALLBACK',candidateSymbols:['LOW'],candidates:[{symbol:'LOW',side:'SELL'}],shortlist:[{symbol:'LOW'}]},decision:{symbol:'LOW',action:'SELL',paperLeverage:5,marginRequired:400,positionValue:2000,stopLossPercent:.7,targetProfitPercent:2.1,cashRiskBudget:20}});
  assert.equal(view.selectionMode,'PRICE_FALLBACK');assert.equal(view.shortlist.length,1);assert.equal(view.leverage,5);assert.equal(view.margin,400);assert.equal(view.stopPercent,.7);
});

test('real exposure ceiling is read separately from paper leverage',()=>{
 const values={fallbackEnabled:{checked:true},fallbackMaxPrice:{value:'450'},paperLeverage:{value:'5'},realMaxLeverage:{value:'3'},priceBandsEnabled:{checked:false}};
 const form={querySelector(s){return values[s.slice(6,-1)];},querySelectorAll(){return [];}};
 assert.equal(readRules(form).realMaxLeverage,3);assert.equal(readRules(form).paperLeverage,5);
});

test('capital ranges have exact cash boundaries and do not use leverage',()=>{
 const {capitalBand,capitalBandRows,defaultCapitalBands}=require('../backend/src/main/resources/static/intraday-rules.js');
 const rules={capitalPriceBands:defaultCapitalBands(),paperLeverage:5,realMaxLeverage:5};
 for(const [capital,max]of [[2000,200],[9999.99,200],[10000,500],[20000,500],[20000.01,3000]])assert.equal(capitalBand(rules,capital).maxSharePrice,max);
 assert.deepEqual(capitalBandRows(rules).map(b=>b.price),['Below ₹200','Below ₹500','Up to ₹3000']);
 assert.equal(capitalBandRows(rules)[2].range,'Above ₹20000');
});
test('capital fields preserve edited boundaries and inclusive settings independently of stop bands',()=>{
 const values={fallbackEnabled:{checked:true},fallbackMaxPrice:{value:300},paperLeverage:{value:5},realMaxLeverage:{value:3},priceBandsEnabled:{checked:false},capitalPriceFilterEnabled:{checked:true}};
 const capitalValues={capitalLimit:{value:15000},capitalLimitInclusive:{checked:true},maxSharePrice:{value:250},sharePriceInclusive:{checked:false}};
 const form={querySelector:s=>values[s.slice(6,-1)],querySelectorAll:s=>s==='[data-capital-band]'?[{querySelector:s=>capitalValues[s.slice(6,-1)]}]:[]};
 const rules=readRules(form);assert.deepEqual(rules.capitalPriceBands,[{capitalLimit:15000,capitalLimitInclusive:true,maxSharePrice:250,sharePriceInclusive:false}]);
 assert.equal(rules.capitalPriceFilterEnabled,true);assert.equal(rules.realMaxLeverage,3);assert.equal(rules.fallbackMaxPrice,300);assert.equal(rules.priceBandsEnabled,false);
});
