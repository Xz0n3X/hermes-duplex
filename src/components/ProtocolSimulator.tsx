import React, { useState, useEffect, useRef } from 'react';
import {
  Activity,
  Send,
  Play,
  Pause,
  AlertOctagon,
  RefreshCw,
  CheckCircle2,
  Copy,
  Terminal,
  Radio,
  Volume2,
  Mic,
  Zap,
} from 'lucide-react';

interface LogEntry {
  id: string;
  timestamp: string;
  direction: 'tx' | 'rx';
  type: string;
  payload: any;
}

export const ProtocolSimulator: React.FC = () => {
  const [isConnected, setIsConnected] = useState(true);
  const [isSpeaking, setIsSpeaking] = useState(false);
  const [logs, setLogs] = useState<LogEntry[]>([
    {
      id: 'log-1',
      timestamp: '11:28:40.100',
      direction: 'tx',
      type: 'session.init',
      payload: {
        clientVersion: '0.79-poc',
        protocolVersion: '1.4-duplex',
        deviceModel: 'Pixel 8 Pro (API 34)',
        audioFormat: { codec: 'OPUS', sampleRate: 24000, channels: 1, frameSizeMs: 20 },
      },
    },
    {
      id: 'log-2',
      timestamp: '11:28:40.180',
      direction: 'rx',
      type: 'session.ready',
      payload: {
        sessionId: 'sess_99384728',
        maxAudioBitrate: 32000,
        bargeInAllowed: true,
      },
    },
  ]);

  const [activeJsonPreview, setActiveJsonPreview] = useState<string>(
    JSON.stringify(logs[1].payload, null, 2)
  );

  const logsEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  // Audio simulation timer
  useEffect(() => {
    let interval: any;
    if (isSpeaking && isConnected) {
      let seq = 1;
      interval = setInterval(() => {
        const entry: LogEntry = {
          id: `chunk-${Date.now()}-${seq}`,
          timestamp: new Date().toLocaleTimeString() + '.' + (Date.now() % 1000).toString().padStart(3, '0'),
          direction: 'tx',
          type: 'audio.chunk',
          payload: {
            turnId: 'turn_001',
            sequence: seq++,
            frameBytes: 960,
            codec: 'OPUS',
            durationMs: 20,
          },
        };
        setLogs((prev) => [...prev.slice(-30), entry]);
      }, 200); // simulated interval
    }
    return () => clearInterval(interval);
  }, [isSpeaking, isConnected]);

  const sendTurnStart = () => {
    const entry: LogEntry = {
      id: `turn-start-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      direction: 'tx',
      type: 'turn.start',
      payload: {
        turnId: `turn_${Date.now().toString().slice(-4)}`,
        trigger: 'voice_detected',
        vadConfidence: 0.96,
      },
    };
    setLogs((prev) => [...prev, entry]);
    setActiveJsonPreview(JSON.stringify(entry.payload, null, 2));
    setIsSpeaking(true);
  };

  const sendInterruption = () => {
    const entry: LogEntry = {
      id: `interrupt-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      direction: 'tx',
      type: 'interruption.detected',
      payload: {
        interruptedTurnId: 'turn_001',
        audioPositionMs: 1420,
        action: 'halt_tts_immediate',
      },
    };
    setLogs((prev) => [...prev, entry]);
    setActiveJsonPreview(JSON.stringify(entry.payload, null, 2));
    setIsSpeaking(false);
  };

  const triggerFailClosed = () => {
    const entry: LogEntry = {
      id: `fail-closed-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      direction: 'tx',
      type: 'error.fail_closed',
      payload: {
        reason: 'BRIDGE_TOKEN_EXPIRED',
        action: 'HALT_MIC_AND_DISCONNECT',
        timestamp: Date.now(),
      },
    };
    setLogs((prev) => [...prev, entry]);
    setActiveJsonPreview(JSON.stringify(entry.payload, null, 2));
    setIsConnected(false);
    setIsSpeaking(false);
  };

  const reconnect = () => {
    setIsConnected(true);
    const entry: LogEntry = {
      id: `reconnect-${Date.now()}`,
      timestamp: new Date().toLocaleTimeString(),
      direction: 'tx',
      type: 'session.init',
      payload: {
        clientVersion: '0.79-poc',
        protocolVersion: '1.4-duplex',
        reconnect: true,
      },
    };
    setLogs((prev) => [...prev, entry]);
    setActiveJsonPreview(JSON.stringify(entry.payload, null, 2));
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-lg">
      <div className="p-4 border-b border-slate-800 bg-slate-950/60 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-cyan-500/10 border border-cyan-500/20 flex items-center justify-center text-cyan-400">
            <Activity className="w-4 h-4" />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-slate-100 flex items-center gap-2">
              WebSocket Protocol Sandbox (PROTOCOL.md)
              <span
                className={`text-[10px] px-2 py-0.5 rounded-full font-mono ${
                  isConnected
                    ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
                    : 'bg-rose-500/10 text-rose-400 border border-rose-500/20'
                }`}
              >
                {isConnected ? 'BRIDGE CONNECTED' : 'DISCONNECTED (FAIL-CLOSED)'}
              </span>
            </h3>
            <p className="text-[11px] text-slate-400">
              Simulate real-time duplex exchange without risking server contract violations.
            </p>
          </div>
        </div>

        {/* Action triggers */}
        <div className="flex items-center gap-2 flex-wrap text-xs">
          {isConnected ? (
            <>
              <button
                onClick={isSpeaking ? () => setIsSpeaking(false) : sendTurnStart}
                className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg font-medium transition cursor-pointer ${
                  isSpeaking
                    ? 'bg-amber-600 hover:bg-amber-500 text-white'
                    : 'bg-emerald-600 hover:bg-emerald-500 text-white'
                }`}
              >
                <Mic className="w-3.5 h-3.5" />
                {isSpeaking ? 'Stop Mic Stream' : 'Simulate Turn Start'}
              </button>

              <button
                onClick={sendInterruption}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-purple-600 hover:bg-purple-500 text-white font-medium transition cursor-pointer"
                title="Barge-In Interruption Event"
              >
                <Zap className="w-3.5 h-3.5" />
                Trigger Barge-In
              </button>

              <button
                onClick={triggerFailClosed}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-rose-600/80 hover:bg-rose-600 text-white font-medium transition cursor-pointer"
                title="Simulate security breach or error"
              >
                <AlertOctagon className="w-3.5 h-3.5" />
                Test Fail-Closed
              </button>
            </>
          ) : (
            <button
              onClick={reconnect}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-cyan-600 hover:bg-cyan-500 text-white font-medium transition cursor-pointer"
            >
              <RefreshCw className="w-3.5 h-3.5" />
              Reconnect Bridge Socket
            </button>
          )}
        </div>
      </div>

      {/* Main split: Console Logs & Live JSON */}
      <div className="grid grid-cols-1 lg:grid-cols-2 divide-y lg:divide-y-0 lg:divide-x divide-slate-800 bg-slate-950 font-mono text-xs">
        {/* Left: Stream Log */}
        <div className="p-3 h-72 overflow-y-auto space-y-1.5">
          <div className="text-[10px] text-slate-500 flex items-center justify-between pb-1 border-b border-slate-900">
            <span>DUPLEX WEBSOCKET TELEMETRY</span>
            <span>PORT 8765</span>
          </div>

          {logs.map((log) => (
            <div
              key={log.id}
              onClick={() => setActiveJsonPreview(JSON.stringify(log.payload, null, 2))}
              className={`p-1.5 rounded cursor-pointer transition flex items-center justify-between ${
                log.direction === 'tx'
                  ? 'hover:bg-cyan-950/40 text-cyan-300'
                  : 'hover:bg-emerald-950/40 text-emerald-300'
              }`}
            >
              <div className="flex items-center gap-2 truncate">
                <span className="text-[10px] text-slate-500">{log.timestamp}</span>
                <span
                  className={`text-[9px] px-1 py-0.2 rounded font-bold ${
                    log.direction === 'tx'
                      ? 'bg-cyan-500/20 text-cyan-400'
                      : 'bg-emerald-500/20 text-emerald-400'
                  }`}
                >
                  {log.direction.toUpperCase()}
                </span>
                <span className="font-semibold text-slate-200">{log.type}</span>
              </div>
              <span className="text-[10px] text-slate-500 shrink-0">view JSON</span>
            </div>
          ))}
          <div ref={logsEndRef} />
        </div>

        {/* Right: Selected Payload JSON preview */}
        <div className="p-3 h-72 overflow-y-auto flex flex-col justify-between bg-slate-950/80">
          <div>
            <div className="flex items-center justify-between pb-1 border-b border-slate-900 text-[10px] text-slate-500">
              <span className="flex items-center gap-1.5 text-slate-400">
                <Terminal className="w-3 h-3 text-cyan-400" />
                ACTIVE PAYLOAD INSPECTOR
              </span>
              <button
                onClick={() => navigator.clipboard.writeText(activeJsonPreview)}
                className="text-slate-400 hover:text-slate-200 transition"
                title="Copy JSON"
              >
                <Copy className="w-3 h-3" />
              </button>
            </div>
            <pre className="text-[11px] text-slate-300 mt-2 overflow-x-auto whitespace-pre-wrap leading-relaxed">
              {activeJsonPreview}
            </pre>
          </div>

          <div className="pt-2 border-t border-slate-900 text-[10px] text-slate-500 flex items-center justify-between">
            <span>Verified schema: PROTOCOL.md v1.4</span>
            <span className="text-emerald-400 flex items-center gap-1">
              <CheckCircle2 className="w-3 h-3" />
              Contract compliant
            </span>
          </div>
        </div>
      </div>
    </div>
  );
};
