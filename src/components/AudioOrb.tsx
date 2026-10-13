import React from 'react';
import { DuplexState } from '../types/app';

interface AudioOrbProps {
  state: DuplexState;
  volume: number; // 0 to 1
  onClick?: () => void;
}

export const AudioOrb: React.FC<AudioOrbProps> = ({ state, volume, onClick }) => {
  // Compute visual scales based on volume and state
  const baseScale = state === 'speaking' || state === 'listening' ? 1 + volume * 0.45 : 1;
  const pulseSpeed =
    state === 'speaking'
      ? 'animate-pulse duration-700'
      : state === 'thinking'
      ? 'animate-spin duration-3000'
      : '';

  // Theme color schemes per duplex state
  let glowColor = 'from-cyan-500 to-blue-600 shadow-cyan-500/30';
  let ringColor = 'border-cyan-500/30';
  let subText = 'Tap to speak';

  switch (state) {
    case 'listening':
      glowColor = 'from-emerald-400 via-teal-500 to-cyan-600 shadow-emerald-500/40';
      ringColor = 'border-emerald-400/40';
      subText = 'Listening...';
      break;
    case 'streaming':
      glowColor = 'from-cyan-400 via-sky-500 to-indigo-600 shadow-cyan-400/50';
      ringColor = 'border-cyan-400/50';
      subText = 'Streaming 24kHz Opus';
      break;
    case 'thinking':
      glowColor = 'from-purple-500 via-indigo-500 to-cyan-500 shadow-purple-500/40';
      ringColor = 'border-purple-400/40';
      subText = 'Mac Bridge processing...';
      break;
    case 'speaking':
      glowColor = 'from-amber-400 via-rose-500 to-purple-600 shadow-rose-500/50';
      ringColor = 'border-rose-400/40';
      subText = 'Hermes speaking (Tap to barge-in)';
      break;
    case 'interrupted':
      glowColor = 'from-rose-500 to-red-600 shadow-rose-500/50';
      ringColor = 'border-rose-500/50';
      subText = 'Barge-in detected!';
      break;
    case 'error_fail_closed':
      glowColor = 'from-red-600 to-rose-900 shadow-red-600/40';
      ringColor = 'border-red-500/40';
      subText = 'Fail-Closed: Audio locked';
      break;
    case 'disconnected':
      glowColor = 'from-slate-700 to-slate-800 shadow-slate-800/20';
      ringColor = 'border-slate-700/30';
      subText = 'Bridge disconnected';
      break;
  }

  return (
    <div className="relative flex flex-col items-center justify-center cursor-pointer select-none py-6" onClick={onClick}>
      {/* Outer pulsating wave ring 1 */}
      <div
        className={`absolute rounded-full border ${ringColor} transition-all duration-300 pointer-events-none`}
        style={{
          width: `${210 * baseScale}px`,
          height: `${210 * baseScale}px`,
          opacity: state === 'listening' || state === 'speaking' ? 0.35 + volume * 0.4 : 0.15,
        }}
      />

      {/* Outer pulsating wave ring 2 */}
      <div
        className={`absolute rounded-full border border-dashed ${ringColor} transition-all duration-500 pointer-events-none`}
        style={{
          width: `${260 * (1 + volume * 0.3)}px`,
          height: `${260 * (1 + volume * 0.3)}px`,
          opacity: state === 'speaking' ? 0.25 : 0.08,
        }}
      />

      {/* Main Dynamic Glowing Orb */}
      <div
        className={`relative w-36 h-36 rounded-full bg-gradient-to-tr ${glowColor} shadow-2xl transition-transform duration-150 flex items-center justify-center overflow-hidden ${pulseSpeed}`}
        style={{
          transform: `scale(${baseScale})`,
        }}
      >
        {/* Internal liquid shimmer overlay */}
        <div className="absolute inset-0 bg-radial from-white/30 via-transparent to-black/40 mix-blend-overlay" />

        {/* Dynamic center waveform bars */}
        <div className="flex items-center gap-1 z-10">
          {[0.4, 0.8, 1, 0.6, 0.3].map((factor, i) => {
            const h =
              state === 'speaking' || state === 'listening'
                ? Math.max(8, 36 * (volume * factor + 0.2))
                : state === 'thinking'
                ? 16 + Math.sin(Date.now() / 200 + i) * 8
                : 6;

            return (
              <div
                key={i}
                className="w-1.5 bg-white/90 rounded-full transition-all duration-100 shadow-sm"
                style={{ height: `${h}px` }}
              />
            );
          })}
        </div>
      </div>

      {/* State label below orb */}
      <span className="mt-6 text-xs font-medium text-slate-300 tracking-wide font-sans">
        {subText}
      </span>
    </div>
  );
};
