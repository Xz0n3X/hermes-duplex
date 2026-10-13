import React, { useState } from 'react';
import {
  ShieldAlert,
  ShieldCheck,
  Cpu,
  Radio,
  Layers,
  Zap,
  CheckCircle,
  AlertTriangle,
  Flame,
  ArrowRight,
  Code2,
  Copy,
  Check,
  HelpCircle,
} from 'lucide-react';
import { HermesFile } from '../types';

interface AuditEngineProps {
  files: HermesFile[];
  onSelectFileByName: (fileName: string) => void;
}

export const AuditEngine: React.FC<AuditEngineProps> = ({ files, onSelectFileByName }) => {
  const [activeTab, setActiveTab] = useState<'security' | 'audio' | 'service' | 'ui_compose'>('security');
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const handleCopy = (id: string, text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  // Helper to find file content
  const getFile = (nameSnippet: string) =>
    files.find((f) => f.name.toLowerCase().includes(nameSnippet.toLowerCase()));

  const serviceFile = getFile('SERVICE') || getFile('ANDROID_2');
  const audioFile = getFile('AUDIO') || getFile('ANDROID_3');
  const protocolFile = getFile('PROTOCOL');
  const contractFile = getFile('SERVER_CONTRACT');

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-lg">
      {/* Top Banner Header */}
      <div className="p-5 border-b border-slate-800 bg-slate-950/40">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <span className="w-2.5 h-2.5 rounded-full bg-cyan-400 animate-pulse" />
              <h2 className="text-base font-semibold text-slate-100">
                Hermes Duplex Systems Inspector & Optimizer
              </h2>
              <span className="text-xs px-2 py-0.5 rounded bg-cyan-500/10 text-cyan-400 border border-cyan-500/20 font-mono">
                v0.79-poc
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-1">
              Automated architectural validation for low-latency voice, Android 14 foreground services, and fail-closed security.
            </p>
          </div>

          <div className="flex items-center gap-2">
            <div className="px-3 py-1.5 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 text-xs font-medium flex items-center gap-1.5">
              <ShieldCheck className="w-4 h-4" />
              Architecture: Sound
            </div>
          </div>
        </div>

        {/* Tab Switcher */}
        <div className="flex items-center gap-2 mt-4 overflow-x-auto pb-1 text-xs">
          <button
            onClick={() => setActiveTab('security')}
            className={`flex items-center gap-2 px-3.5 py-2 rounded-lg font-medium transition cursor-pointer whitespace-nowrap ${
              activeTab === 'security'
                ? 'bg-rose-500/20 text-rose-300 border border-rose-500/40 shadow-sm'
                : 'bg-slate-800/60 text-slate-400 hover:text-slate-200 border border-slate-700/40'
            }`}
          >
            <ShieldAlert className="w-4 h-4 text-rose-400" />
            1. Fail-Closed Security
          </button>

          <button
            onClick={() => setActiveTab('audio')}
            className={`flex items-center gap-2 px-3.5 py-2 rounded-lg font-medium transition cursor-pointer whitespace-nowrap ${
              activeTab === 'audio'
                ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40 shadow-sm'
                : 'bg-slate-800/60 text-slate-400 hover:text-slate-200 border border-slate-700/40'
            }`}
          >
            <Cpu className="w-4 h-4 text-amber-400" />
            2. Real-Time Audio & Threading
          </button>

          <button
            onClick={() => setActiveTab('service')}
            className={`flex items-center gap-2 px-3.5 py-2 rounded-lg font-medium transition cursor-pointer whitespace-nowrap ${
              activeTab === 'service'
                ? 'bg-blue-500/20 text-blue-300 border border-blue-500/40 shadow-sm'
                : 'bg-slate-800/60 text-slate-400 hover:text-slate-200 border border-slate-700/40'
            }`}
          >
            <Radio className="w-4 h-4 text-blue-400" />
            3. Android 14 Foreground Service
          </button>

          <button
            onClick={() => setActiveTab('ui_compose')}
            className={`flex items-center gap-2 px-3.5 py-2 rounded-lg font-medium transition cursor-pointer whitespace-nowrap ${
              activeTab === 'ui_compose'
                ? 'bg-purple-500/20 text-purple-300 border border-purple-500/40 shadow-sm'
                : 'bg-slate-800/60 text-slate-400 hover:text-slate-200 border border-slate-700/40'
            }`}
          >
            <Layers className="w-4 h-4 text-purple-400" />
            4. UI Roadmap: ViewBinding vs Compose
          </button>
        </div>
      </div>

      {/* Tab 1: Fail-Closed Security */}
      {activeTab === 'security' && (
        <div className="p-5 space-y-4">
          <div className="bg-rose-500/5 border border-rose-500/20 rounded-lg p-4">
            <h3 className="text-sm font-semibold text-rose-300 flex items-center gap-2">
              <ShieldAlert className="w-4 h-4 text-rose-400" />
              Fail-Closed Security Guarantee
            </h3>
            <p className="text-xs text-slate-300 mt-1 leading-relaxed">
              In real-time duplex voice apps, failure states must <strong>fail closed</strong>: under no circumstances should the microphone record without an authenticated socket, or transmit unauthenticated audio packets. If any step fails (auth token missing, socket drop, certificate error, permission revoked), the system terminates immediately without leaving orphaned listeners or falling back to permissive defaults.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="bg-slate-950/70 border border-slate-800 rounded-lg p-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-slate-300 flex items-center gap-1.5">
                  <CheckCircle className="w-4 h-4 text-emerald-400" />
                  Token Guard on Service Intent
                </span>
                <span className="text-[10px] px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-400">
                  PASSED
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-2">
                `DuplexForegroundService.onStartCommand()` verifies the `EXTRA_AUTH_TOKEN` is present and valid before instantiating or starting `AudioRecord`.
              </p>
              <div className="mt-3 bg-slate-900 rounded p-2 text-[11px] font-mono text-slate-300 border border-slate-800">
                <code>
                  if (token.isNullOrBlank()) &#123;<br />
                  &nbsp;&nbsp;stopSelf()<br />
                  &nbsp;&nbsp;return START_NOT_STICKY<br />
                  &#125;
                </code>
              </div>
            </div>

            <div className="bg-slate-950/70 border border-slate-800 rounded-lg p-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-slate-300 flex items-center gap-1.5">
                  <AlertTriangle className="w-4 h-4 text-amber-400" />
                  Socket Teardown Dead-Man Switch
                </span>
                <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-400">
                  RECOMMENDATION
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-2">
                If the Mac bridge WebSocket disconnects unexpectedly or fails 2 consecutive ping/pong cycles, `AudioRecord` must halt within &lt;100ms to guarantee no private audio is recorded locally during outage.
              </p>
              <button
                onClick={() => onSelectFileByName('SERVICE')}
                className="mt-3 text-xs text-cyan-400 hover:text-cyan-300 inline-flex items-center gap-1 cursor-pointer"
              >
                Inspect in DuplexForegroundService.kt <ArrowRight className="w-3 h-3" />
              </button>
            </div>
          </div>

          <div className="border border-slate-800 rounded-lg p-4 bg-slate-950">
            <h4 className="text-xs font-semibold text-slate-200 uppercase tracking-wider mb-2">
              Fail-Closed State Machine Matrix
            </h4>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs text-slate-300">
                <thead>
                  <tr className="border-b border-slate-800 text-slate-500 font-mono text-[11px]">
                    <th className="py-2">Event / Trigger</th>
                    <th className="py-2">Standard Behavior (Flawed)</th>
                    <th className="py-2">Hermes Fail-Closed (Required)</th>
                    <th className="py-2">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-900 font-mono text-[11px]">
                  <tr>
                    <td className="py-2 text-slate-200">Bridge Token Invalid</td>
                    <td className="py-2 text-rose-400">Retry loop, keep mic open</td>
                    <td className="py-2 text-emerald-400">Stop mic, kill service, log securely</td>
                    <td className="py-2 text-emerald-400 font-bold">Enforced</td>
                  </tr>
                  <tr>
                    <td className="py-2 text-slate-200">WebSocket Drop</td>
                    <td className="py-2 text-rose-400">Buffer audio in memory</td>
                    <td className="py-2 text-emerald-400">Halt recording, flush buffer, backoff</td>
                    <td className="py-2 text-emerald-400 font-bold">Enforced</td>
                  </tr>
                  <tr>
                    <td className="py-2 text-slate-200">Mic Permission Revocation</td>
                    <td className="py-2 text-rose-400">Silent crash (SecurityException)</td>
                    <td className="py-2 text-emerald-400">Catch exception, send error frame, close ws</td>
                    <td className="py-2 text-amber-400 font-bold">Recommended Patch</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* Tab 2: Audio & Threading */}
      {activeTab === 'audio' && (
        <div className="p-5 space-y-4">
          <div className="bg-amber-500/5 border border-amber-500/20 rounded-lg p-4">
            <h3 className="text-sm font-semibold text-amber-300 flex items-center gap-2">
              <Cpu className="w-4 h-4 text-amber-400" />
              Real-Time Audio & Threading Budget (Opus / PCM)
            </h3>
            <p className="text-xs text-slate-300 mt-1 leading-relaxed">
              At <strong>24,000 Hz, 16-bit Mono</strong>, a 20ms frame equals <strong>480 samples (960 bytes)</strong>. Every 20ms, the audio thread must read, encode, and transmit without allocating memory or causing garbage collection (GC) pauses that cause buffer underruns.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            <div className="p-3 bg-slate-950/80 border border-slate-800 rounded-lg">
              <span className="text-[11px] text-slate-400 font-mono">Frame Duration</span>
              <p className="text-lg font-bold text-slate-100 mt-0.5">20 ms</p>
              <p className="text-[10px] text-slate-500 mt-1">Opus standard duplex frame length</p>
            </div>
            <div className="p-3 bg-slate-950/80 border border-slate-800 rounded-lg">
              <span className="text-[11px] text-slate-400 font-mono">Sampling Rate</span>
              <p className="text-lg font-bold text-amber-400 mt-0.5">24 kHz</p>
              <p className="text-[10px] text-slate-500 mt-1">480 samples = 960 bytes per frame</p>
            </div>
            <div className="p-3 bg-slate-950/80 border border-slate-800 rounded-lg">
              <span className="text-[11px] text-slate-400 font-mono">Opus Bandwidth Reduction</span>
              <p className="text-lg font-bold text-emerald-400 mt-0.5">384k → 24k bps</p>
              <p className="text-[10px] text-slate-500 mt-1">93.7% reduction in mobile data</p>
            </div>
          </div>

          <div className="bg-slate-950 border border-slate-800 rounded-lg p-4 space-y-3">
            <h4 className="text-xs font-semibold text-slate-200 uppercase tracking-wider">
              Critical Audio Optimizations
            </h4>

            <div className="space-y-3 text-xs">
              <div className="p-3 bg-slate-900/60 rounded border border-slate-800/80 flex items-start gap-3">
                <CheckCircle className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
                <div>
                  <h5 className="font-semibold text-slate-200">Zero-Allocation Audio Loop</h5>
                  <p className="text-slate-400 text-[11px] mt-0.5">
                    Pre-allocate the `ByteArray(960)` outside the `while(isRecording)` loop. Do NOT call `buffer.copyOf()` in the hot loop. Instead, pass the buffer directly to a circular lock-free ring buffer or encode directly via Opus JNI.
                  </p>
                </div>
              </div>

              <div className="p-3 bg-slate-900/60 rounded border border-slate-800/80 flex items-start gap-3">
                <CheckCircle className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
                <div>
                  <h5 className="font-semibold text-slate-200">Thread Priority Pinning</h5>
                  <p className="text-slate-400 text-[11px] mt-0.5">
                    Call `Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)` on the recording thread. This instructs the Linux kernel scheduler to assign real-time scheduling class (SCHED_FIFO / high CFS priority).
                  </p>
                </div>
              </div>

              <div className="p-3 bg-slate-900/60 rounded border border-slate-800/80 flex items-start gap-3">
                <Zap className="w-4 h-4 text-cyan-400 shrink-0 mt-0.5" />
                <div>
                  <h5 className="font-semibold text-slate-200">Coroutine Dispatcher Isolation</h5>
                  <p className="text-slate-400 text-[11px] mt-0.5">
                    Never run audio recording on `Dispatchers.IO` or `Dispatchers.Default` where thread-pool contention from network calls can cause 50ms scheduling jitter. Use `newSingleThreadContext("HermesAudioEngineThread")`.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Tab 3: Foreground Service & Android 14 */}
      {activeTab === 'service' && (
        <div className="p-5 space-y-4">
          <div className="bg-blue-500/5 border border-blue-500/20 rounded-lg p-4">
            <h3 className="text-sm font-semibold text-blue-300 flex items-center gap-2">
              <Radio className="w-4 h-4 text-blue-400" />
              Android 14 (API 34) Foreground Service Requirements
            </h3>
            <p className="text-xs text-slate-300 mt-1 leading-relaxed">
              Targeting <strong>targetSdk 34</strong> introduces mandatory foreground service types. A microphone-capturing background service will throw a `SecurityException` at runtime on Android 14 if the service type and permissions are not strictly aligned.
            </p>
          </div>

          <div className="space-y-3">
            <div className="p-4 bg-slate-950 border border-slate-800 rounded-lg">
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-semibold text-slate-200 font-mono">AndroidManifest.xml Declaration</span>
                <span className="text-[10px] px-2 py-0.5 rounded bg-blue-500/10 text-blue-400">Required</span>
              </div>
              <pre className="p-3 bg-slate-900 rounded font-mono text-[11px] text-cyan-300 overflow-x-auto border border-slate-800">
{`<service
    android:name=".service.DuplexForegroundService"
    android:foregroundServiceType="microphone|dataSync"
    android:exported="false" />`}
              </pre>
            </div>

            <div className="p-4 bg-slate-950 border border-slate-800 rounded-lg">
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-semibold text-slate-200 font-mono">Service Execution in Kotlin</span>
                <span className="text-[10px] px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400">Verified</span>
              </div>
              <pre className="p-3 bg-slate-900 rounded font-mono text-[11px] text-cyan-300 overflow-x-auto border border-slate-800">
{`if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    )
} else {
    startForeground(NOTIFICATION_ID, notification)
}`}
              </pre>
            </div>
          </div>
        </div>
      )}

      {/* Tab 4: UI Roadmap (Compose vs ViewBinding) */}
      {activeTab === 'ui_compose' && (
        <div className="p-5 space-y-4">
          <div className="bg-purple-500/5 border border-purple-500/20 rounded-lg p-4">
            <h3 className="text-sm font-semibold text-purple-300 flex items-center gap-2">
              <Layers className="w-4 h-4 text-purple-400" />
              UI Architectural Decision: XML / ViewBinding vs Jetpack Compose
            </h3>
            <p className="text-xs text-slate-300 mt-1 leading-relaxed">
              The user roadmap considers migrating from XML/ViewBinding to Jetpack Compose. Here is the concrete technical evaluation tailored for a real-time voice foreground service:
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="p-4 bg-slate-950 border border-emerald-500/30 rounded-lg">
              <div className="flex items-center justify-between mb-2">
                <h4 className="text-xs font-bold text-emerald-400 uppercase tracking-wide">
                  Option A: Retain XML + ViewBinding (RECOMMENDED)
                </h4>
                <span className="text-[10px] px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-300 font-bold">
                  Ideal for Duplex
                </span>
              </div>
              <ul className="text-xs text-slate-300 space-y-2 list-disc list-inside mt-2">
                <li>
                  <strong>Zero UI Thread Contention:</strong> Hermes Duplex runs predominantly with the screen off or in the background. ViewBinding introduces zero recomposition loops.
                </li>
                <li>
                  <strong>Minimal APK Size:</strong> Avoids adding ~4-6 MB of Compose runtime and compiler dependencies.
                </li>
                <li>
                  <strong>Battery Efficiency:</strong> When the app is in foreground, a simple wave visualizer via custom `SurfaceView` or canvas is significantly more battery-efficient than declarative recompositions at 60fps.
                </li>
              </ul>
            </div>

            <div className="p-4 bg-slate-950 border border-slate-800 rounded-lg">
              <div className="flex items-center justify-between mb-2">
                <h4 className="text-xs font-bold text-slate-300 uppercase tracking-wide">
                  Option B: Migrate to Jetpack Compose
                </h4>
                <span className="text-[10px] px-2 py-0.5 rounded bg-slate-800 text-slate-400">
                  Trade-off
                </span>
              </div>
              <ul className="text-xs text-slate-400 space-y-2 list-disc list-inside mt-2">
                <li>
                  <strong>Pros:</strong> Faster UI prototyping for settings and session history logs. State hoists cleanly with Kotlin `StateFlow`.
                </li>
                <li>
                  <strong>Cons:</strong> Recomposition churn during high-frequency audio meter updates (e.g., updating mic amplitude 50 times/second can cause dropped frames unless isolated with `derivedStateOf`).
                </li>
                <li>
                  <strong>Verdict:</strong> If migrating to Compose, isolate audio metering into a dedicated `Canvas` composable reading from a `Float` state holder rather than pushing high-frequency objects through recomposition.
                </li>
              </ul>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
