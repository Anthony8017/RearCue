// Explicit protocol simulator, loaded only by the isolated acceptance command. No model calls.
import cp from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { EventEmitter } from 'node:events';
import fs from 'node:fs';
const realSpawn = cp.spawn;
class MockCodex extends EventEmitter {
  constructor() {
    super(); this.stdout = new EventEmitter(); this.stderr = new EventEmitter();
    this.stdout.setEncoding = this.stderr.setEncoding = () => {};
    this.stdin = { write: (text, callback) => {
      const request = JSON.parse(text);
      if (request.result?.answers || request.result?.contentItems) {
        fs.writeFileSync(process.env.RCU_SMOKE_RESULT, JSON.stringify(request));
        queueMicrotask(() => this.output({ method:'serverRequest/resolved', params:{threadId:'rearcue-choice-smoke',requestId:9000} }));
        if(request.result.contentItems) setTimeout(() => this.output({method:'item/completed',params:{threadId:'rearcue-choice-smoke',item:{id:'smoke-ask',type:'dynamicToolCall',status:'completed',success:true,contentItems:request.result.contentItems}}}),50);
        setTimeout(() => this.output({method:'item/completed',params:{threadId:'rearcue-choice-smoke',item:{type:'agentMessage',text:'隔离测试：整组答案已收到'}}}),100);
        setTimeout(() => this.output({method:'turn/completed',params:{threadId:'rearcue-choice-smoke',turn:{id:'turn-smoke',status:'completed'}}}),150);
        callback?.(); return;
      }
      const result = { initialize:{}, 'project/list':{data:[{id:'smoke',title:'RearCue 隔离验收',roots:[{path:process.cwd()}]}]},
        'model/list':{data:[{id:'mock',model:'mock',displayName:'隔离测试',isDefault:true}]},
        'thread/start':{thread:{id:'rearcue-choice-smoke'}}, 'turn/start':{turn:{id:'turn-smoke'}} }[request.method] || {};
      queueMicrotask(() => this.output({id:request.id,result}));
      if (request.method==='turn/start') setTimeout(() => {
        this.output({method:'turn/started',params:{threadId:'rearcue-choice-smoke',turn:{id:'turn-smoke'}}});
        this.output({id:9000,method:'item/tool/call',params:{threadId:'rearcue-choice-smoke',turnId:'turn-smoke',callId:'smoke-ask',tool:'rearcue_choice_question',arguments:{questions:[
          {id:'q1',question:'隔离验收：选择方案',options:[{label:'方案甲',description:'先完成核心功能'},{label:'方案乙',description:'先完善外观'}]},
          {id:'q2',question:'隔离验收：选择顺序',options:[{label:'先测试',description:'验证后发布'},{label:'先发布',description:'发布后观察'}]},
        ]}}});
      },750);
    }};
  }
  output(value) { this.stdout.emit('data',JSON.stringify(value)+'\n'); }
  kill() { queueMicrotask(()=>{this.emit('exit',0,null);this.emit('close',0,null);}); }
}
cp.spawn = (command,args,options) => args?.[0]==='app-server' ? new MockCodex() : realSpawn(command,args,options);
syncBuiltinESMExports();
const stop = process.env.RCU_SMOKE_STOP;
const timer = setInterval(()=>{if(fs.existsSync(stop)){fs.writeFileSync(process.env.BRIDGE_LOG+'.stopflag','planned isolated acceptance exit');clearInterval(timer);process.emit('SIGINT');}},250);
