import React from 'react';
import {
  Mic,
  MicOff,
  Zap,
  Volume2,
  Headphones,
  ShieldCheck,
  ShieldAlert,
  Radio,
  Activity,
  Layers,
  Sparkles,
} from 'lucide-react';
import { DuplexState, ChatTurn } from '../types/app';
import { AudioOrb } from './AudioOrb';

interface VoiceDuplexScreenProps {
  duplexState: DuplexState;
  volume: number;
  lastUserText: string;
  lastServerTurn: ChatTurn | null;
  onMicToggle: () => void;
  onBargeIn: () => void;
  audioRoute: 'speaker' | 'earpiece' | 'bluetooth';
  onChangeAudioRoute: (route: 'speaker' | 'earpiece' | 'bluetooth') => void;
  failClosedSecure: boolean;
}

export const VoiceDuplexScreen: React.FC<VoiceDuplexScreenProps> = ({
  duplexState,
  volume,
  lastUserText,
  lastServerTurn,
  onMicToggle,
  onBargeIn,
  audioRoute,
  onChangeAudioRoute,
  failClosedSecure,
}) => {
  const isMicActive = duplexState === 'listening' || duplexState === 'streaming';
  const isServerSpeaking = duplexState === 'speaking';

  return (
    <div className="flex-1 flex flex-col justify-between px-4 py-6 max-w-md mx-auto w-full select-none">
      {/* Top Telemetry & Security Header */}
      <div className="flex items-center justify-between text-xs">
        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-slate-900 border border-slate-800 text-slate-300 font-mono">
          <Activity className="w-3.5 h-3.5 text-cyan-400" />
          <span>19ms RTT</span>
          <span className="text-slate-600">·</span>
          <span>Opus 24kHz</span>
        </div>

        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-slate-900 border border-slate-800 text-slate-300">
          {failClosedSecure ? (
            <>
              <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
              <span className="text-[11px] text-emerald-400 font-medium">Fail-Closed Secured</span>
            </>
          ) : (
            <>
              <ShieldAlert className="w-3.5 h-3.5 text-rose-400" />
              <span className="text-[11px] text-rose-400 font-medium">Security Tripped</span>
            </>
          )}
        </div>
      </div>

      {/* Central Visualizer Area */}
      <div className="my-auto flex flex-col items-center justify-center">
        <AudioOrb
          state={duplexState}
          volume={volume}
          onClick={isServerSpeaking ? onBargeIn : onMicToggle}
        />

        {/* Live Subtitle / Transcript Banner */}
        <div className="mt-4 w-full min-h-[58px] p-3 rounded-2xl bg-slate-900/60 border border-slate-800/80 backdrop-blur text-center flex flex-col items-center justify-center">
          {isServerSpeaking && lastServerTurn ? (
            <p className="text-xs text-cyan-200 font-medium animate-fadeIn leading-relaxed line-clamp-2">
              "{lastServerTurn.text}"
            </p>
          ) : lastUserText ? (
            <p className="text-xs text-emerald-300 font-medium leading-relaxed line-clamp-2">
              "{lastUserText}"
            </p>
          ) : (
            <p className="text-xs text-slate-500 font-sans">
              Speak naturally into your device microphone or tap to talk.
            </p>
          )}
        </div>
      </div>

      {/* Bottom Controls Panel */}
      <div className="space-y-4">
        {/* Audio Route Selector (Speaker / Earpiece / Bluetooth) */}
        <div className="flex items-center justify-center gap-2 text-xs">
          <button
            onClick={() => onChangeAudioRoute('speaker')}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl font-medium transition cursor-pointer ${
              audioRoute === 'speaker'
                ? 'bg-slate-800 text-cyan-300 border border-cyan-500/30'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Volume2 className="w-3.5 h-3.5" />
            Speaker
          </button>

          <button
            onClick={() => onChangeAudioRoute('earpiece')}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl font-medium transition cursor-pointer ${
              audioRoute === 'earpiece'
                ? 'bg-slate-800 text-cyan-300 border border-cyan-500/30'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Headphones className="w-3.5 h-3.5" />
            Earpiece
          </button>

          <button
            onClick={() => onChangeAudioRoute('bluetooth')}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl font-medium transition cursor-pointer ${
              audioRoute === 'bluetooth'
                ? 'bg-slate-800 text-cyan-300 border border-cyan-500/30'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Radio className="w-3.5 h-3.5" />
            Bluetooth SCO
          </button>
        </div>

        {/* Primary Action Buttons */}
        <div className="flex items-center justify-center gap-4">
          {/* Barge-In Interruption Button */}
          <button
            onClick={onBargeIn}
            className="flex-1 max-w-[130px] flex items-center justify-center gap-2 py-3 px-4 rounded-2xl bg-purple-600/20 hover:bg-purple-600/30 text-purple-300 border border-purple-500/30 text-xs font-semibold transition cursor-pointer"
            title="Interrupt current audio turn"
          >
            <Zap className="w-4 h-4 text-purple-400" />
            Barge-In
          </button>

          {/* Main Mic Toggle Button */}
          <button
            onClick={onMicToggle}
            className={`flex-1 max-w-[160px] flex items-center justify-center gap-2.5 py-3.5 px-5 rounded-2xl font-semibold text-sm transition shadow-lg cursor-pointer ${
              isMicActive
                ? 'bg-emerald-500 hover:bg-emerald-400 text-slate-950 shadow-emerald-500/20'
                : 'bg-cyan-600 hover:bg-cyan-500 text-white shadow-cyan-600/20'
            }`}
          >
            {isMicActive ? <Mic className="w-5 h-5" /> : <MicOff className="w-5 h-5" />}
            <span>{isMicActive ? 'Mic Active' : 'Start Mic'}</span>
          </button>
        </div>
      </div>
    </div>
  );
};
