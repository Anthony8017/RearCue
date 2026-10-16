// App-server system-boundary simulator. The real bridge HTTP stack runs unchanged.
import cp from "node:child_process";
import { EventEmitter } from "node:events";
import { readFileSync } from "node:fs";
import { syncBuiltinESMExports } from "node:module";

const realSpawn = cp.spawn;
class OptionsServer extends EventEmitter {
  constructor() {
    super();
    this.stdout = new EventEmitter();
    this.stderr = new EventEmitter();
    this.stdout.setEncoding = this.stderr.setEncoding = () => {};
    this.stdin = { write: (payload, callback) => {
      const { id, method, params } = JSON.parse(payload);
      const fixture = JSON.parse(readFileSync(process.env.RCU_OPTIONS_FIXTURE, "utf8"));
      let result;
      let error;
      if (method === "initialize") result = {};
      else if (fixture.fail?.includes(method)) error = { code: -32000, message: "sensitive fixture error must not reach HTTP" };
      else if (fixture.pages?.[method]) {
        const page = fixture.pages[method].find(p => p.cursor === (params.cursor ?? null));
        if (page) result = { data: page.data, nextCursor: page.nextCursor };
        else error = { code: -32602, message: "unexpected page cursor" };
      }
      else if (method === "project/list") result = { data: fixture.projects, nextCursor: null };
      else if (method === "model/list") result = { data: fixture.models, nextCursor: null };
      else if (method === "config/read") result = { config: fixture.configs[params.cwd ?? "global"], origins: {}, layers: null };
      else if (method === "permissionProfile/list") result = { data: fixture.profiles, nextCursor: null };
      else if (method === "configRequirements/read") result = { requirements: fixture.requirements };
      else error = { code: -32601, message: `Forbidden fixture method: ${method}` };
      queueMicrotask(() => this.stdout.emit("data", JSON.stringify({ id, ...(error ? { error } : { result }) }) + "\n"));
      callback?.();
    } };
  }
  kill() { queueMicrotask(() => { this.emit("exit", 0, null); this.emit("close", 0, null); }); }
}
cp.spawn = (command, args, options) => command === "codex-options-fixture" && args?.[0] === "app-server"
  ? new OptionsServer() : realSpawn(command, args, options);
syncBuiltinESMExports();
