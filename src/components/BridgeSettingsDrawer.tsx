import React, { useState } from 'react';
import {
  X,
  Server,
  Key,
  ShieldCheck,
  ShieldAlert,
  Zap,
  Radio,
  Sliders,
  FolderSync,
  LogOut,
  User as UserIcon,
  CheckCircle,
} from 'lucide-react';
import { User } from 'firebase/auth';
import { BridgeConfig } from '../types/app';
import { googleSignIn, logout } from '../services/auth';

interface BridgeSettingsDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  config: BridgeConfig;
  onSaveConfig: (newConfig: BridgeConfig) => void;
  user: User | null;
  onSyncDriveTranscripts: () => void;
  isSyncingDrive: boolean;
}

export const BridgeSettingsDrawer: React.FC<BridgeSettingsDrawerProps> = ({
  isOpen,
  onClose,
  config,
  onSaveConfig,
  user,
  onSyncDriveTranscripts,
  isSyncingDrive,
}) => {
  const [localConfig, setLocalConfig] = useState<BridgeConfig>(config);
  const [savedSuccess, setSavedSuccess] = useState(false);

  if (!isOpen) return null;

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    onSaveConfig(localConfig);
    setSavedSuccess(true);
    setTimeout(() => {
      setSavedSuccess(false);
      onClose();
    }, 1200);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-xs select-none">
      <div className="w-full max-w-md bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
        {/* Header */}
        <div className="px-5 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-950/60">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-cyan-500/10 border border-cyan-500/20 flex items-center justify-center text-cyan-400">
              <Sliders className="w-4 h-4" />
            </div>
            <div>
              <h3 className="font-semibold text-slate-100 text-sm">Bridge & Systems Configuration</h3>
              <p className="text-[11px] text-slate-400 font-mono">org.hermes.duplex settings</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Settings Form */}
        <form onSubmit={handleSave} className="p-5 overflow-y-auto space-y-4 flex-1 text-xs">
          {savedSuccess && (
            <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-300 flex items-center gap-2">
              <CheckCircle className="w-4 h-4 text-emerald-400 shrink-0" />
              <span>Bridge parameters updated successfully!</span>
            </div>
          )}

          {/* Mac Bridge Address */}
          <div className="space-y-1.5">
            <label className="text-slate-300 font-medium flex items-center gap-1.5">
              <Server className="w-3.5 h-3.5 text-cyan-400" />
              Mac Bridge Server URL
            </label>
            <input
              type="text"
              value={localConfig.serverUrl}
              onChange={(e) => setLocalConfig({ ...localConfig, serverUrl: e.target.value })}
              placeholder="ws://192.168.1.50:8765"
              className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-slate-200 font-mono focus:outline-none focus:border-cyan-500 transition"
            />
            <p className="text-[10px] text-slate-500">
              Standard default port is 8765 as defined in SERVER_CONTRACT.md
            </p>
          </div>

          {/* Token Authentication */}
          <div className="space-y-1.5">
            <label className="text-slate-300 font-medium flex items-center gap-1.5">
              <Key className="w-3.5 h-3.5 text-cyan-400" />
              Authentication Token (X-Hermes-Token)
            </label>
            <input
              type="password"
              value={localConfig.authToken}
              onChange={(e) => setLocalConfig({ ...localConfig, authToken: e.target.value })}
              placeholder="Secret bridge authentication token"
              className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-slate-200 font-mono focus:outline-none focus:border-cyan-500 transition"
            />
          </div>

          {/* Fail-Closed Security Switch */}
          <div className="p-3 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <ShieldCheck className="w-4 h-4 text-emerald-400" />
                <span className="font-semibold text-slate-200">Fail-Closed Enforcement</span>
              </div>
              <input
                type="checkbox"
                checked={localConfig.failClosedEnabled}
                onChange={(e) =>
                  setLocalConfig({ ...localConfig, failClosedEnabled: e.target.checked })
                }
                className="w-4 h-4 rounded accent-cyan-500 cursor-pointer"
              />
            </div>
            <p className="text-[11px] text-slate-400 leading-relaxed">
              When enabled, any missing token or dropped WebSocket heartbeat terminates AudioRecord instantly with zero memory retention.
            </p>
          </div>

          {/* Simulator Toggle */}
          <div className="p-3 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Radio className="w-4 h-4 text-cyan-400" />
                <span className="font-semibold text-slate-200">Built-in Bridge Simulator</span>
              </div>
              <input
                type="checkbox"
                checked={localConfig.useBuiltinSimulator}
                onChange={(e) =>
                  setLocalConfig({ ...localConfig, useBuiltinSimulator: e.target.checked })
                }
                className="w-4 h-4 rounded accent-cyan-500 cursor-pointer"
              />
            </div>
            <p className="text-[11px] text-slate-400 leading-relaxed">
              Enables real-time duplex speech responses in the browser when your local Mac bridge server is offline.
            </p>
          </div>

          {/* Audio Codec & Frame Size */}
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <label className="text-slate-400 font-medium">Audio Codec</label>
              <select
                value={localConfig.codec}
                onChange={(e) =>
                  setLocalConfig({ ...localConfig, codec: e.target.value as 'OPUS' | 'PCM_16BIT' })
                }
                className="w-full bg-slate-950 border border-slate-800 rounded-xl px-2.5 py-2 text-slate-200 font-mono focus:outline-none"
              >
                <option value="OPUS">Opus (24 kbps)</option>
                <option value="PCM_16BIT">PCM 16-Bit (384 kbps)</option>
              </select>
            </div>

            <div className="space-y-1">
              <label className="text-slate-400 font-medium">Sampling Rate</label>
              <select
                value={localConfig.sampleRate}
                onChange={(e) =>
                  setLocalConfig({ ...localConfig, sampleRate: parseInt(e.target.value) })
                }
                className="w-full bg-slate-950 border border-slate-800 rounded-xl px-2.5 py-2 text-slate-200 font-mono focus:outline-none"
              >
                <option value="24000">24 kHz (Standard)</option>
                <option value="16000">16 kHz (Low Bandwidth)</option>
              </select>
            </div>
          </div>

          {/* Google Drive Account & Sync Status */}
          <div className="p-3 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
            <div className="flex items-center justify-between">
              <span className="font-semibold text-slate-200 flex items-center gap-1.5">
                <FolderSync className="w-4 h-4 text-emerald-400" />
                Google Drive Storage Sync
              </span>
              {user ? (
                <span className="text-[10px] px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-400 font-mono">
                  Connected
                </span>
              ) : (
                <button
                  type="button"
                  onClick={() => googleSignIn()}
                  className="text-[11px] text-cyan-400 hover:text-cyan-300 font-medium cursor-pointer"
                >
                  Connect Drive
                </button>
              )}
            </div>

            {user && (
              <div className="flex items-center justify-between pt-1">
                <span className="text-slate-400 truncate max-w-[200px]">
                  {user.displayName || user.email}
                </span>
                <button
                  type="button"
                  onClick={onSyncDriveTranscripts}
                  disabled={isSyncingDrive}
                  className="px-2.5 py-1 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-[11px] font-medium transition cursor-pointer disabled:opacity-40"
                >
                  {isSyncingDrive ? 'Syncing...' : 'Sync Session'}
                </button>
              </div>
            )}
          </div>

          {/* Bottom Actions */}
          <div className="pt-2 flex items-center gap-3">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 font-medium transition cursor-pointer"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="flex-1 py-2.5 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white font-medium transition shadow-md cursor-pointer"
            >
              Apply Changes
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
