/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect, useRef } from 'react';
import { User } from 'firebase/auth';
import {
  Mic,
  MessageSquare,
  Sliders,
  Code,
  Radio,
  FolderSync,
  Maximize2,
  Minimize2,
  Smartphone,
  ExternalLink,
  Zap,
  Github,
} from 'lucide-react';
import { initAuth, getAccessToken } from './services/auth';
import { DuplexState, ChatTurn, BridgeConfig, ServiceState } from './types/app';
import { HermesFile } from './types';
import { DEFAULT_HERMES_FILES } from './data/defaultProject';
import { AudioEngine } from './services/audioEngine';
import { BridgeClient } from './services/bridgeClient';
import { AndroidServiceBar } from './components/AndroidServiceBar';
import { VoiceDuplexScreen } from './components/VoiceDuplexScreen';
import { ChatTranscriptScreen } from './components/ChatTranscriptScreen';
import { BridgeSettingsDrawer } from './components/BridgeSettingsDrawer';
import { ModernSourceCodeModal } from './components/ModernSourceCodeModal';
import { GitHubConnectModal } from './components/GitHubConnectModal';
import { FileImportSyncDrawer } from './components/FileImportSyncDrawer';

const DEFAULT_CONFIG: BridgeConfig = {
  serverUrl: 'ws://192.168.1.50:8765',
  authToken: 'hermes-sec-token-77491',
  codec: 'OPUS',
  sampleRate: 24000,
  frameSizeMs: 20,
  heartbeatIntervalMs: 15000,
  vadSensitivity: 0.85,
  failClosedEnabled: true,
  useBuiltinSimulator: true, // Enables immediate interactive testability
};

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [activeTab, setActiveTab] = useState<'voice' | 'transcript'>('voice');
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isCodeModalOpen, setIsCodeModalOpen] = useState(false);
  const [isGitHubModalOpen, setIsGitHubModalOpen] = useState(false);
  const [isFilesDrawerOpen, setIsFilesDrawerOpen] = useState(false);
  const [isMobileFrame, setIsMobileFrame] = useState(true);
  const [workspaceFiles, setWorkspaceFiles] = useState<HermesFile[]>(DEFAULT_HERMES_FILES);

  // App & Audio States
  const [duplexState, setDuplexState] = useState<DuplexState>('idle');
  const [audioVolume, setAudioVolume] = useState<number>(0);
  const [lastUserSpeech, setLastUserSpeech] = useState<string>('');
  const [turns, setTurns] = useState<ChatTurn[]>([
    {
      id: 'welcome-turn',
      sender: 'server',
      text: 'Hermes Duplex v0.79 initialized. Low-latency bridge ready on 24kHz Opus.',
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      latencyMs: 17,
    },
  ]);
  const [audioRoute, setAudioRoute] = useState<'speaker' | 'earpiece' | 'bluetooth'>('speaker');
  const [config, setConfig] = useState<BridgeConfig>(DEFAULT_CONFIG);
  const [isSyncingDrive, setIsSyncingDrive] = useState(false);

  // Service State
  const [serviceState, setServiceState] = useState<ServiceState>({
    isActive: true,
    wakeLockHeld: true,
    notificationId: 1001,
    serviceType: 'FOREGROUND_SERVICE_MICROPHONE',
    uptimeSeconds: 0,
    batteryDrainRatePercentPerHour: 1.8,
  });

  const audioEngineRef = useRef<AudioEngine>(new AudioEngine());
  const bridgeClientRef = useRef<BridgeClient | null>(null);

  // Initialize Auth
  useEffect(() => {
    const unsub = initAuth(
      (currentUser) => setUser(currentUser),
      () => setUser(null)
    );
    return () => unsub();
  }, []);

  // Initialize Bridge Client
  useEffect(() => {
    const bridge = new BridgeClient(
      config,
      (newState) => {
        setDuplexState(newState);
        if (newState === 'speaking') {
          // Play speech via audio engine
          const latestTurn = turns[turns.length - 1];
          if (latestTurn && latestTurn.sender === 'server') {
            audioEngineRef.current.speak(latestTurn.text, () => {
              setDuplexState('listening');
            });
          }
        }
      },
      (newTurn) => {
        setTurns((prev) => [...prev, newTurn]);
      }
    );
    bridgeClientRef.current = bridge;
    bridge.connect();

    // Volume monitoring
    audioEngineRef.current.onVolume((vol) => {
      setAudioVolume(vol);
    });

    audioEngineRef.current.onSpeech((text) => {
      setLastUserSpeech(text);
      // Auto-send user speech turn
      handleSendTurn(text);
    });

    return () => {
      bridge.disconnect();
      audioEngineRef.current.stopMic();
    };
  }, []);

  const handleMicToggle = async () => {
    if (duplexState === 'listening' || duplexState === 'streaming') {
      audioEngineRef.current.stopMic();
      setDuplexState('idle');
    } else {
      setDuplexState('listening');
      const micOk = await audioEngineRef.current.initMic();
      if (!micOk) {
        // Fallback simulated volume pulse if mic permission in iframe is restricted
        const interval = setInterval(() => {
          setAudioVolume(Math.random() * 0.7);
        }, 120);
        setTimeout(() => clearInterval(interval), 3000);
      }
    }
  };

  const handleBargeIn = () => {
    audioEngineRef.current.stopSpeaking();
    bridgeClientRef.current?.sendInterruption();
    setDuplexState('interrupted');
    setTimeout(() => {
      setDuplexState('listening');
    }, 400);
  };

  const handleSendTurn = (text: string) => {
    const userTurn: ChatTurn = {
      id: 'turn-u-' + Date.now(),
      sender: 'user',
      text,
      timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    };
    setTurns((prev) => [...prev, userTurn]);
    setDuplexState('thinking');
    bridgeClientRef.current?.sendTurn(text);
  };

  const handleEmergencyFailClosed = () => {
    audioEngineRef.current.stopSpeaking();
    audioEngineRef.current.stopMic();
    bridgeClientRef.current?.disconnect('error_fail_closed');
    setDuplexState('error_fail_closed');
    setServiceState((prev) => ({ ...prev, isActive: false, wakeLockHeld: false }));
  };

  const handleSyncTranscriptToDrive = async () => {
    setIsSyncingDrive(true);
    try {
      const token = await getAccessToken();
      if (!token) {
        alert('Please connect your Google Drive account first in Settings.');
        setIsSyncingDrive(false);
        return;
      }

      const fileData = {
        sessionDate: new Date().toISOString(),
        totalTurns: turns.length,
        config,
        turns,
      };

      const metadata = {
        name: `hermes_duplex_session_${Date.now()}.json`,
        mimeType: 'application/json',
      };

      const form = new FormData();
      form.append(
        'metadata',
        new Blob([JSON.stringify(metadata)], { type: 'application/json' })
      );
      form.append('file', new Blob([JSON.stringify(fileData, null, 2)], { type: 'application/json' }));

      const res = await fetch(
        'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart',
        {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${token}`,
          },
          body: form,
        }
      );

      if (res.ok) {
        alert('Session transcript successfully backed up to Google Drive!');
      } else {
        alert('Failed to upload transcript to Google Drive. Check permissions.');
      }
    } catch (err: any) {
      alert(`Error saving to Google Drive: ${err.message}`);
    } finally {
      setIsSyncingDrive(false);
    }
  };

  const lastServerTurn = [...turns].reverse().find((t) => t.sender === 'server') || null;

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col items-center justify-center p-2 sm:p-6 selection:bg-cyan-500/30 selection:text-cyan-200">
      {/* Top Floating App Bar */}
      <header className="w-full max-w-md sm:max-w-xl mb-3 flex items-center justify-between px-2 text-xs">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded-lg bg-cyan-500 flex items-center justify-center text-slate-950 font-bold text-xs">
            H
          </div>
          <div>
            <h1 className="font-bold text-slate-200 tracking-tight">Hermes Duplex</h1>
            <span className="text-[10px] text-slate-400 font-mono">Modernized Android Client</span>
          </div>
        </div>

        <div className="flex items-center gap-1.5 flex-wrap">
          <button
            onClick={() => setIsGitHubModalOpen(true)}
            className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-slate-200 border border-slate-800 transition cursor-pointer text-xs"
            title="Connect & Push to GitHub / SSH"
          >
            <Github className="w-3.5 h-3.5 text-white" />
            <span className="hidden sm:inline">GitHub & SSH</span>
          </button>

          <button
            onClick={() => setIsFilesDrawerOpen(true)}
            className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-slate-200 border border-slate-800 transition cursor-pointer text-xs"
            title="Import or view original Android app files"
          >
            <FolderSync className="w-3.5 h-3.5 text-emerald-400" />
            <span className="hidden sm:inline">Files ({workspaceFiles.length})</span>
          </button>

          <button
            onClick={() => setIsCodeModalOpen(true)}
            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-slate-300 border border-slate-800 transition cursor-pointer text-xs"
            title="View Modern Kotlin & Compose Source Code"
          >
            <Code className="w-3.5 h-3.5 text-cyan-400" />
            <span className="hidden sm:inline">Kotlin Source</span>
          </button>

          <button
            onClick={() => setIsSettingsOpen(true)}
            className="p-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-slate-300 border border-slate-800 transition cursor-pointer"
            title="Bridge & Audio Settings"
          >
            <Sliders className="w-4 h-4 text-slate-300" />
          </button>

          <button
            onClick={() => setIsMobileFrame(!isMobileFrame)}
            className="p-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-slate-300 border border-slate-800 transition cursor-pointer hidden sm:block"
            title={isMobileFrame ? 'Expand to Desktop View' : 'Switch to Mobile Frame'}
          >
            {isMobileFrame ? <Maximize2 className="w-4 h-4" /> : <Minimize2 className="w-4 h-4" />}
          </button>
        </div>
      </header>

      {/* Main Android Phone Mockup Frame */}
      <div
        className={`w-full transition-all duration-300 ${
          isMobileFrame
            ? 'max-w-[420px] h-[820px] rounded-[44px] border-[8px] border-slate-800 shadow-[0_25px_60px_-15px_rgba(0,0,0,0.7)]'
            : 'max-w-2xl h-[840px] rounded-3xl border border-slate-800 shadow-2xl'
        } bg-slate-950 flex flex-col overflow-hidden relative`}
      >
        {/* Dynamic Island / Camera Notch */}
        {isMobileFrame && (
          <div className="absolute top-2.5 left-1/2 -translate-x-1/2 w-28 h-4.5 bg-black rounded-full z-50 flex items-center justify-center pointer-events-none">
            <div className="w-2.5 h-2.5 rounded-full bg-slate-900 border border-slate-800 mr-2" />
            <div className="w-1.5 h-1.5 rounded-full bg-cyan-900/40" />
          </div>
        )}

        {/* Android 14 Status Bar & Foreground Service Drawer */}
        <AndroidServiceBar
          duplexState={duplexState}
          serviceState={serviceState}
          onEmergencyStop={handleEmergencyFailClosed}
        />

        {/* Main Screen Content */}
        <div className="flex-1 flex flex-col overflow-hidden">
          {activeTab === 'voice' ? (
            <VoiceDuplexScreen
              duplexState={duplexState}
              volume={audioVolume}
              lastUserText={lastUserSpeech}
              lastServerTurn={lastServerTurn}
              onMicToggle={handleMicToggle}
              onBargeIn={handleBargeIn}
              audioRoute={audioRoute}
              onChangeAudioRoute={setAudioRoute}
              failClosedSecure={duplexState !== 'error_fail_closed'}
            />
          ) : (
            <ChatTranscriptScreen
              turns={turns}
              onSendMessage={handleSendTurn}
              onPlayTurnAudio={(text) => audioEngineRef.current.speak(text)}
              onClearTranscript={() => setTurns([])}
              onSaveToDrive={handleSyncTranscriptToDrive}
              isSavingToDrive={isSyncingDrive}
            />
          )}
        </div>

        {/* Modern Android 3-Button / Pill Navigation Bar */}
        <nav className="border-t border-slate-900 bg-slate-950/90 backdrop-blur px-6 py-2.5 flex items-center justify-around select-none">
          <button
            onClick={() => setActiveTab('voice')}
            className={`flex flex-col items-center gap-1 transition cursor-pointer ${
              activeTab === 'voice' ? 'text-cyan-400 font-semibold' : 'text-slate-500 hover:text-slate-300'
            }`}
          >
            <Mic className="w-5 h-5" />
            <span className="text-[10px] tracking-wide">Duplex Voice</span>
          </button>

          <button
            onClick={() => setActiveTab('transcript')}
            className={`flex flex-col items-center gap-1 transition cursor-pointer ${
              activeTab === 'transcript' ? 'text-cyan-400 font-semibold' : 'text-slate-500 hover:text-slate-300'
            }`}
          >
            <MessageSquare className="w-5 h-5" />
            <span className="text-[10px] tracking-wide">Transcript</span>
          </button>

          <button
            onClick={() => setIsSettingsOpen(true)}
            className="flex flex-col items-center gap-1 text-slate-500 hover:text-slate-300 transition cursor-pointer"
          >
            <Sliders className="w-5 h-5" />
            <span className="text-[10px] tracking-wide">Bridge Setup</span>
          </button>
        </nav>

        {/* Android Home Indicator Pill */}
        {isMobileFrame && (
          <div className="pb-2 pt-1 flex justify-center bg-slate-950">
            <div className="w-32 h-1 bg-slate-700 rounded-full" />
          </div>
        )}
      </div>

      {/* Settings Drawer Modal */}
      <BridgeSettingsDrawer
        isOpen={isSettingsOpen}
        onClose={() => setIsSettingsOpen(false)}
        config={config}
        onSaveConfig={(newConfig) => {
          setConfig(newConfig);
          bridgeClientRef.current?.updateConfig(newConfig);
        }}
        user={user}
        onSyncDriveTranscripts={handleSyncTranscriptToDrive}
        isSyncingDrive={isSyncingDrive}
      />

      {/* Kotlin & Compose Source Code Modal */}
      <ModernSourceCodeModal
        isOpen={isCodeModalOpen}
        onClose={() => setIsCodeModalOpen(false)}
      />

      {/* GitHub & SSH Connect Modal */}
      <GitHubConnectModal
        isOpen={isGitHubModalOpen}
        onClose={() => setIsGitHubModalOpen(false)}
        allProjectFiles={workspaceFiles.map((f) => ({
          path: f.name.endsWith('.kt')
            ? `app/src/main/java/org/hermes/duplex/${f.name}`
            : f.name.endsWith('.md')
            ? `docs/${f.name}`
            : f.name,
          content: f.content,
        }))}
      />

      {/* Project Files & Google Drive Importer Drawer */}
      <FileImportSyncDrawer
        isOpen={isFilesDrawerOpen}
        onClose={() => setIsFilesDrawerOpen(false)}
        files={workspaceFiles}
        onAddOrUpdateFile={(file) => {
          setWorkspaceFiles((prev) => {
            const filtered = prev.filter((f) => f.id !== file.id && f.name !== file.name);
            return [...filtered, file];
          });
        }}
        onDeleteFile={(id) => {
          setWorkspaceFiles((prev) => prev.filter((f) => f.id !== id));
        }}
        user={user}
        onFilesLoadedFromDrive={(driveFiles) => {
          setWorkspaceFiles((prev) => {
            const names = new Set(driveFiles.map((d) => d.name));
            const existing = prev.filter((f) => !names.has(f.name));
            return [...existing, ...driveFiles];
          });
        }}
      />
    </div>
  );
}
