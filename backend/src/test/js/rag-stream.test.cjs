const test = require("node:test");
const assert = require("node:assert/strict");
const { createParser } = require("../../main/resources/static/rag-stream.js");

test("dispatches received text before the final answer event exists", () => {
  const received = [];
  const parser = createParser(event => received.push(event));
  parser.push('event: delta\ndata: {"text":"方法是"}\n\n');
  assert.deepEqual(received, [{ type: "delta", data: '{"text":"方法是"}' }]);
  parser.push('event: delta\ndata: {"text":"完成任务的技术。"}\n\n');
  assert.equal(received.length, 2);
  parser.push('event: done\ndata: {"status":"generated"}\n\n');
  parser.finish();
  assert.equal(received[2].type, "done");
});

test("handles every possible byte boundary in Chinese and surrogate-pair text with split CRLF", () => {
  const wire = new TextEncoder().encode('\ufeff: keepalive\r\nevent: delta\r\ndata: {"text":"方法、工具 🧩\\n过程"}\r\n\r\nevent: done\r\ndata: {"status":"generated"}\r\n\r\n');
  for (let size = 1; size <= wire.length; size++) {
    const events = [], decoder = new TextDecoder("utf-8", { fatal: true });
    const parser = createParser(event => events.push({ type: event.type, data: JSON.parse(event.data) }));
    for (let at = 0; at < wire.length; at += size) parser.push(decoder.decode(wire.subarray(at, at + size), { stream: true }));
    parser.push(decoder.decode()); parser.finish();
    assert.deepEqual(events, [
      { type: "delta", data: { text: "方法、工具 🧩\n过程" } },
      { type: "done", data: { status: "generated" } }
    ], `byte chunk size ${size}`);
  }
});

test("accepts multiline event data, CR-only lines and comments without inventing events", () => {
  const events = [], parser = createParser(event => events.push(event));
  parser.push(': hello\revent: delta\rdata: {\rdata: "text": "甲"}\r\r: heartbeat\r\r');
  parser.finish();
  assert.equal(events.length, 1);
  assert.deepEqual(JSON.parse(events[0].data), { text: "甲" });
});

test("rejects an unfinished terminal event instead of treating the connection close as done", () => {
  for (const suffix of ['event: done', 'event: done\ndata: {"status":"generated"}', 'event: done\ndata: {"status":"generated"}\n']) {
    const events = [], parser = createParser(event => events.push(event));
    parser.push('event: delta\ndata: {"text":"草稿"}\n\n' + suffix);
    assert.equal(events.length, 1);
    assert.throws(() => parser.finish(), /Truncated/);
  }
});

test("bounds both unbroken lines and events split across many data fields", () => {
  assert.throws(() => createParser(() => {}, 20).push('data: ' + 'a'.repeat(21)), /too large/);
  assert.throws(() => createParser(() => {}, 20).push('data: aaaaaaaaaa\ndata: bbbbbbbbbb\n\n'), /too large/);
});
