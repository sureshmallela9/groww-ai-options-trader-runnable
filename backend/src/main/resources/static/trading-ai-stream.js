(function (root) {
  "use strict";
  function createParser(onEvent) {
    let line = "", pendingCR = false, first = true, event = "message", data = [];
    function dispatch() {
      if (data.length) onEvent({ event: event, data: data.join("\n") });
      event = "message"; data = [];
    }
    function processLine() {
      const value = line; line = "";
      if (!value) { dispatch(); return; }
      if (value.startsWith(":")) return;
      const colon = value.indexOf(":");
      const field = colon < 0 ? value : value.slice(0, colon);
      let content = colon < 0 ? "" : value.slice(colon + 1);
      if (content.startsWith(" ")) content = content.slice(1);
      if (field === "event") event = content || "message";
      if (field === "data") data.push(content);
    }
    return {
      push(text) {
        for (const character of text) {
          if (first) { first = false; if (character === "\ufeff") continue; }
          if (pendingCR) { pendingCR = false; if (character === "\n") continue; }
          if (character === "\r") { processLine(); pendingCR = true; }
          else if (character === "\n") processLine();
          else line += character;
        }
      },
      finish() { if (line) processLine(); dispatch(); pendingCR = false; }
    };
  }
  async function consume(body, onEvent) {
    const reader = body.getReader(), decoder = new TextDecoder(), parser = createParser(onEvent);
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        parser.push(decoder.decode(chunk.value, { stream: true }));
      }
      parser.push(decoder.decode()); parser.finish();
    } finally { reader.releaseLock(); }
  }
  const api = { createParser: createParser, consume: consume };
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TradingAiStream = api;
})(globalThis);
