/** Only an explicit final phase is an answer; unknown streaming phases remain reclassifiable. */
export function codexAssistantKind(phase, channel) {
  return ["final_answer", "final"].includes(String(phase || channel || "").toLowerCase())
    ? "answer" : "progress";
}

/** Codex renders this trailing metadata separately from the visible answer body. */
export function codexMessageText(value) {
  return String(value || "").replace(/\s*<oai-mem-citation>[\s\S]*?<\/oai-mem-citation>\s*$/, "").trim();
}
