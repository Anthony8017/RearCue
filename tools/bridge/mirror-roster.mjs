import { existsSync, readFileSync, writeFileSync, renameSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";

export const mirrorKey = (source, sessionId) => JSON.stringify([source || "legacy", sessionId]);

/** Mirror eligibility is independent of source archive truth. Commit before publishing. */
export class MirrorRoster {
  constructor(file, { now = Date.now(), bootstrap = [] } = {}) {
    this.file = file;
    this.error = null;
    this.data = { version: 1, activatedAt: now, revision: 0, choices: {} };
    try {
      if (existsSync(file)) {
        const value = JSON.parse(readFileSync(file, "utf8"));
        if (value.version !== 1 || !Number.isFinite(value.activatedAt) || value.activatedAt < 0 || value.activatedAt > 8_000_000_000_000 ||
            !Number.isSafeInteger(value.revision) || value.revision < 0 || !value.choices || Array.isArray(value.choices) ||
            Object.values(value.choices).some((v) => typeof v !== "boolean")) throw new Error("名单文件格式无效");
        this.data = value;
      } else {
        for (const row of bootstrap) this.data.choices[mirrorKey(row.source, row.sessionId)] = true;
        this.save(this.data);
      }
    } catch (error) {
      this.error = `镜像名单无法读取或保存：${error.message}`;
    }
  }
  save(data) {
    mkdirSync(dirname(this.file), { recursive: true });
    const temporary = `${this.file}.tmp`;
    writeFileSync(temporary, JSON.stringify(data) + "\n", { mode: 0o600 });
    renameSync(temporary, this.file);
  }
  choice(row) { return this.data.choices[mirrorKey(row.source, row.sessionId)]; }
  allows(row) {
    if (this.error) return false;
    const choice = this.choice(row);
    if (choice !== undefined) return choice;
    return Number.isFinite(row.createdAt) && row.createdAt >= this.data.activatedAt;
  }
  apply(changes, revision, resolve) {
    if (this.error) throw new Error(this.error);
    if (revision !== this.data.revision) throw new Error("名单已经变化，请刷新后重试");
    if (!Array.isArray(changes) || changes.length > 10000) throw new Error("请选择有效对话");
    const next = { ...this.data, choices: { ...this.data.choices }, revision: revision + 1 };
    const changed = [];
    for (const change of changes) {
      if (typeof change?.enabled !== "boolean") throw new Error("名单操作无效");
      const row = resolve(change.source, change.sessionId);
      if (!row || (change.enabled && (!row.available || row.archived || row.internal))) {
        throw new Error("对话已归档、删除或来源不可用，请刷新后重试");
      }
      next.choices[mirrorKey(row.source, row.sessionId)] = change.enabled;
      changed.push(row);
    }
    this.save(next); // Failure leaves the old in-memory and published choices intact.
    this.data = next;
    return changed;
  }
}
