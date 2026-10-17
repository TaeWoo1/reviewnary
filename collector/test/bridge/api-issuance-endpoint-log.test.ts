import { beforeEach, describe, expect, it } from "vitest";
import type { WebSocket } from "ws";
import { ApiIssuanceEndpoint } from "../../src/bridge/api-issuance-endpoint";
import { ACTION_WINDOW_PROTOCOL_VERSION } from "../../../contracts/action-window/v2";
import { serializeFrame } from "../../../contracts/action-window/v2/transport";
import { clearLogSink, getLogSink } from "../../src/log";

/** An open socket that swallows what it is sent — the endpoint only needs `readyState` and `send`. */
function fakeSocket(): WebSocket {
  return { readyState: 1, send: () => undefined } as unknown as WebSocket;
}

const RUN_ID = "run_0123456789ab";
const COMMAND_ID = "cmd-log-probe-7f3e";

function events(name: string) {
  return getLogSink().filter((e) => e.event === name);
}

describe("issuance endpoint — what the tab sent, by enum only", () => {
  beforeEach(() => clearLogSink());

  it("logs a resync and a command by kind and command type, never the envelope's ids", () => {
    const endpoint = new ApiIssuanceEndpoint({ runId: RUN_ID, channelCode: "naver" });
    const ws = fakeSocket();
    endpoint.onClientConnected(ws);
    endpoint.onClientPayload(ws, serializeFrame({ kind: "aw_resync", runId: RUN_ID, sinceSequence: 0 }));
    endpoint.onClientPayload(
      ws,
      serializeFrame({
        kind: "aw_command",
        command: { protocolVersion: ACTION_WINDOW_PROTOCOL_VERSION, commandId: COMMAND_ID, runId: RUN_ID, expectedRevision: 0, type: "CANCEL_RUN" },
      }),
    );

    expect(events("aw_issuance_frame_received").map((e) => e.meta)).toEqual([
      { kind: "aw_resync" },
      { kind: "aw_command", command: "CANCEL_RUN" },
    ]);
    const logged = JSON.stringify(getLogSink());
    expect(logged).not.toContain(RUN_ID);
    expect(logged).not.toContain(COMMAND_ID);
  });

  it("logs a malformed frame as dropped, not as received", () => {
    const endpoint = new ApiIssuanceEndpoint({ runId: RUN_ID, channelCode: "naver" });
    const ws = fakeSocket();
    endpoint.onClientConnected(ws);
    endpoint.onClientPayload(ws, "{not json");
    expect(events("aw_issuance_frame_received")).toHaveLength(0);
    expect(events("aw_issuance_frame_malformed")).toHaveLength(1);
  });

  it("logs a detach with the remaining count, once per socket it knew", () => {
    const endpoint = new ApiIssuanceEndpoint({ runId: RUN_ID, channelCode: "naver" });
    const a = fakeSocket();
    const b = fakeSocket();
    endpoint.onClientConnected(a);
    endpoint.onClientConnected(b);
    endpoint.onClientDisconnected(a);
    endpoint.onClientDisconnected(a); // the bridge reports close AND error for one socket
    endpoint.onClientDisconnected(fakeSocket()); // never attached here
    expect(events("aw_issuance_client_detached").map((e) => e.meta)).toEqual([{ carrier: "issuance", clients: 1 }]);
  });
});
