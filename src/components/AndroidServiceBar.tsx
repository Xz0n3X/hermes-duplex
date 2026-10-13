import React, { useState } from 'react';
import {
  Wifi,
  Battery,
  Shield,
  Radio,
  Mic,
  Cpu,
  ChevronDown,
  ChevronUp,
  Square,
  Lock,
} from 'lucide-react';
import { DuplexState, ServiceState } from '../types/app';

interface AndroidServiceBarProps {
  duplexState: DuplexState;
  serviceState: ServiceState;
  onEmergencyStop: () => void;
}

export const AndroidServiceBar: React.FC<AndroidServiceBarProps> = ({
  duplexState,
  serviceState,
  onEmergencyStop,
}) => {
  const [showNotificationDrawer, setShowNotificationDrawer] = useState(false);
  const currentTime = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

  return (
    <div className="w-full bg-slate-950/95 border-b border-slate-800/80 select-none">
      {/* Android Status Bar */}
      <div className="px-5 py-1.5 flex items-center justify-between text-[11px] text-slate-400 font-sans tracking-tight">
        <div className="flex items-center gap-2">
          <span className="font-semibold text-slate-200">{currentTime}</span>
          {serviceState.isActive && (
            <span className="inline-flex items-center gap-1 text-[10px] text-emerald-400">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
              <Mic className="w-3 h-3 text-emerald-400" />
            </span>
          )}
        </div>

        <div className="flex items-center gap-3">
          <span className="text-[10px] text-slate-500 font-mono">5G</span>
          <Wifi className="w-3.5 h-3.5 text-slate-300" />
          <div className="flex items-center gap-1">
            <Battery className="w-4 h-4 text-emerald-400" />
            <span className="text-[10px] font-mono text-slate-300">96%</span>
          </div>
        </div>
      </div>

      {/* Android 14 Foreground Service Notification Card */}
      <div className="px-4 py-2 bg-slate-900/90 border-t border-slate-800/60">
        <div
          onClick={() => setShowNotificationDrawer(!showNotificationDrawer)}
          className="flex items-center justify-between cursor-pointer group"
        >
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="w-7 h-7 rounded-lg bg-cyan-500/10 border border-cyan-500/20 flex items-center justify-center text-cyan-400 shrink-0">
              <Radio className="w-3.5 h-3.5" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span className="text-xs font-semibold text-slate-200 truncate">
                  Hermes Duplex Service
                </span>
                <span className="text-[9px] px-1.5 py-0.2 rounded bg-cyan-500/10 text-cyan-400 font-mono border border-cyan-500/20">
                  API 34
                </span>
              </div>
              <p className="text-[11px] text-slate-400 truncate">
                {duplexState === 'error_fail_closed'
                  ? 'Fail-Closed Active • Microphone hardware released'
                  : 'Foreground Service Active • Low-latency duplex voice'}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            {showNotificationDrawer ? (
              <ChevronUp className="w-4 h-4 text-slate-400 group-hover:text-slate-200 transition" />
            ) : (
              <ChevronDown className="w-4 h-4 text-slate-400 group-hover:text-slate-200 transition" />
            )}
          </div>
        </div>

        {/* Expanded Notification Drawer Details */}
        {showNotificationDrawer && (
          <div className="mt-3 pt-3 border-t border-slate-800 text-xs space-y-2.5">
            <div className="grid grid-cols-2 gap-2 text-[11px] font-mono">
              <div className="p-2 rounded bg-slate-950 border border-slate-800">
                <span className="text-slate-500">Service Type</span>
                <p className="text-slate-200 mt-0.5">FOREGROUND_SERVICE_MICROPHONE</p>
              </div>
              <div className="p-2 rounded bg-slate-950 border border-slate-800">
                <span className="text-slate-500">WakeLock</span>
                <p className="text-emerald-400 mt-0.5">PARTIAL_WAKE_LOCK (Held)</p>
              </div>
            </div>

            <div className="flex items-center justify-between pt-1">
              <span className="text-[11px] text-slate-400">
                Thread Priority: <strong className="text-cyan-400">THREAD_PRIORITY_URGENT_AUDIO</strong>
              </span>

              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onEmergencyStop();
                }}
                className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded bg-rose-600/20 hover:bg-rose-600/30 text-rose-300 text-[11px] font-medium transition border border-rose-500/30 cursor-pointer"
              >
                <Lock className="w-3 h-3 text-rose-400" />
                Emergency Fail-Closed Stop
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
