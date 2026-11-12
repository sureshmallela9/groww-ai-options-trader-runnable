const {test}=require('node:test'),assert=require('node:assert/strict');
const news=require('../backend/src/main/resources/static/workspace-news.js');
const routes=require('../backend/src/main/resources/static/workspace-router.js');
class Element{constructor(tag){this.tag=tag;this.children=[];this._text='';}set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+' '+this.children.map(x=>x.textContent).join(' ');}set innerHTML(v){throw Error('No untrusted HTML');}append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this._text='';this.children=nodes;}}
test('News has a real reloadable route',()=>{assert.equal(routes.resolve('/workspace/news').name,'news');assert.equal(routes.pathFor('news'),'/workspace/news');});
test('partial daily reports show missing US data and source limitations without inventing results',()=>{
  const target=new Element('div');target.ownerDocument={createElement:tag=>new Element(tag)};
  news.render(target,{reportDate:'2026-10-30',status:'PARTIAL',executiveSummary:['No available today news.'],usMarket:{status:'UNAVAILABLE',observations:[],interpretations:[]},newsPlan:{lookbackUsed:'LAST_7_DAYS',candidates:[],bearishCandidates:[]},limitations:['No fill is fabricated.']});
  assert.match(target.textContent,/US market results are not available/);assert.match(target.textContent,/Previous seven days/);assert.match(target.textContent,/No final entry decision/);
});
test('headline text stays inert and non-HTTP source links are rejected',()=>{
  const target=new Element('div');target.ownerDocument={createElement:tag=>new Element(tag)};
  news.render(target,{reportDate:'2026-10-30',newsPlan:{lookbackUsed:'TODAY',candidates:[{symbol:'TEST',newsBias:'POSITIVE',newsScore:.4,headlines:[{headline:'<img src=x onerror=alert(1)>',sourceUrl:'javascript:alert(1)'}]}]}});
  assert.match(target.textContent,/<img src=x onerror=alert\(1\)>/);
  const nodes=n=>[n,...n.children.flatMap(nodes)];assert.equal(nodes(target).filter(n=>n.tag==='a').length,0);
});
