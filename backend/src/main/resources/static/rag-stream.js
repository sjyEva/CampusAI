/* Incremental SSE framing only. No buffering of a complete answer or typing animation. */
((root) => {
  "use strict";

  function createParser(onEvent, maxEventChars = 262144) {
    let line = "", eventType = "", data = [], eventChars = 0, afterCr = false;
    function acceptLine() {
      const current = line;
      line = "";
      if (current === "") {
        if (data.length) onEvent({ type: eventType || "message", data: data.join("\n") });
        eventType = ""; data = []; eventChars = 0;
        return;
      }
      if (current.startsWith(":")) return;
      const colon = current.indexOf(":");
      const field = colon < 0 ? current : current.slice(0, colon);
      let value = colon < 0 ? "" : current.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "event") eventType = value;
      else if (field === "data") {
        eventChars += value.length + 1;
        if (eventChars > maxEventChars) throw new Error("SSE event is too large");
        data.push(value);
      }
    }
    return {
      push(text) {
        for (const char of text) {
          if (afterCr) { afterCr = false; if (char === "\n") continue; }
          if (char === "\r" || char === "\n") { acceptLine(); afterCr = char === "\r"; }
          else {
            line += char;
            if (line.length > maxEventChars) throw new Error("SSE line is too large");
          }
        }
      },
      finish() {
        // A partially received data/event line must not be mistaken for a completed answer.
        if (data.length || eventType || (line && !line.startsWith(":"))) throw new Error("Truncated SSE event");
      }
    };
  }

  const api = Object.freeze({ createParser });
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.CampusAiStream = api;
})(typeof globalThis === "undefined" ? this : globalThis);
