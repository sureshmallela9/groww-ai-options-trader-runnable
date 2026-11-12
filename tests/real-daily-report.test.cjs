const {test}=require('node:test'),assert=require('node:assert/strict');
const {buildDailyReport}=require('../backend/src/main/resources/static/daily-orders.js');
test('partial live exits contribute only their recorded day P&L without claiming the position is closed',()=>{
 const row={intentId:'real-one',symbol:'TEST',mode:'REAL',state:'POSITION_OPEN',filledQuantity:3,remainingQuantity:2,entryPrice:100,entryTime:'2026-11-06T05:00:00Z',realizedPnl:.8,realizedByDay:{'2026-11-06':{net:.8,charges:.2,gross:1,exitQuantity:1}}};
 const report=buildDailyReport([row],'2026-11-06');assert.equal(report.realizedPnl,.8);assert.equal(report.charges,.2);assert.equal(report.exits,0);assert.equal(report.entries,1);assert.equal(report.rows[0].exitDay,null);
 assert.equal(buildDailyReport([row],'2026-11-05').realizedPnl,0);
});
