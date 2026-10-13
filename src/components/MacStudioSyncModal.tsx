import React, { useState } from 'react';
import {
  X,
  Server,
  Download,
  Terminal,
  FolderSync,
  Send,
  Check,
  Copy,
  Sparkles,
  ExternalLink,
  Laptop,
} from 'lucide-react';
import {
  downloadAndroidProjectZip,
  getMacStudioTerminalBootstrapScript,
  ANDROID_STUDIO_PROJECT_FILES,
} from '../services/projectExporter';
import { getAccessToken } from '../services/auth';

interface MacStudioSyncModalProps {
  isOpen: boolean;
  onClose: () => void;
  serverUrl: string;
}

export const MacStudioSyncModal: React.FC<MacStudioSyncModalProps> = ({
  isOpen,
  onClose,
  serverUrl,
}) => {
  const [copiedScript, setCopiedScript] = useState(false);
  const [isExportingZip, setIsExportingZip] = useState(false);
  const [isSendingToBridge, setIsSendingToBridge] = useState(false);
  const [bridgeSendStatus, setBridgeSendStatus] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'zip' | 'terminal' | 'network' | 'drive'>('zip');

  if (!isOpen) return null;

  const handleDownloadZip = async () => {
    setIsExportingZip(true);
    try {
      await downloadAndroidProjectZip('HermesDuplex-AndroidStudio.zip');
    } catch (err: any) {
      alert(`Error creating zip: ${err.message}`);
    } finally {
      setIsExportingZip(false);
    }
  };

  const handleCopyTerminalCommand = () => {
    const script = getMacStudioTerminalBootstrapScript();
    navigator.clipboard.writeText(script);
    setCopiedScript(true);
    setTimeout(() => setCopiedScript(null), 2500);
  };

  const handleSendToBridge = async () => {
    setIsSendingToBridge(true);
    setBridgeSendStatus('Dispatching files to MacStudio...');

    const payload = {
      project: 'HermesDuplex',
      target: 'AndroidStudio',
      timestamp: Date.now(),
      files: ANDROID_STUDIO_PROJECT_FILES,
    };

    try {
      // Try posting to local bridge receiver endpoint
      const targetUrl = serverUrl.replace(/^ws:\/\//, 'http://').replace(/^wss:\/\//, 'https://');
      const res = await fetch(`${targetUrl}/api/receive-project`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (res.ok) {
        setBridgeSendStatus('Files successfully transferred to MacStudio bridge!');
      } else {
        setBridgeSendStatus(`Bridge responded with status: ${res.status}. Alternatively, use the ZIP download.`);
      }
    } catch {
      // Local bridge might only accept WebSocket or not have an HTTP endpoint
      setBridgeSendStatus('Transferred payload prepared. You can download the complete ZIP or paste the Terminal command below.');
    } finally {
      setIsSendingToBridge(false);
    }
  };

  const handleSaveZipToGoogleDrive = async () => {
    try {
      const token = await getAccessToken();
      if (!token) {
        alert('Please connect Google Drive in Settings first.');
        return;
      }

      setBridgeSendStatus('Uploading HermesDuplex-AndroidStudio.zip to Google Drive...');
      const { generateAndroidProjectZip } = await import('../services/projectExporter');
      const zipBlob = await generateAndroidProjectZip();

      const metadata = {
        name: 'HermesDuplex-AndroidStudio.zip',
        mimeType: 'application/zip',
      };

      const form = new FormData();
      form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
      form.append('file', zipBlob);

      const res = await fetch(
        'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart',
        {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}` },
          body: form,
        }
      );

      if (res.ok) {
        setBridgeSendStatus('Uploaded directly to your Google Drive! Your MacStudio will sync it automatically.');
      } else {
        setBridgeSendStatus('Upload failed. Check Google Drive permissions.');
      }
    } catch (err: any) {
      setBridgeSendStatus(`Error: ${err.message}`);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-xs select-none">
      <div className="w-full max-w-2xl bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
        {/* Header */}
        <div className="px-5 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-950/60">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-cyan-500/10 border border-cyan-500/20 flex items-center justify-center text-cyan-400">
              <Laptop className="w-5 h-5" />
            </div>
            <div>
              <h3 className="font-semibold text-slate-100 text-sm">
                Send Project to MacStudio for Android Studio Build
              </h3>
              <p className="text-xs text-slate-400 font-mono">
                Full Gradle project structure • Kotlin • Jetpack Compose • Target SDK 34
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Tab Selection */}
        <div className="px-5 pt-3 bg-slate-950 border-b border-slate-800 flex items-center gap-2 text-xs">
          <button
            onClick={() => setActiveTab('zip')}
            className={`flex items-center gap-1.5 px-3 py-2 rounded-t-lg font-medium transition cursor-pointer ${
              activeTab === 'zip'
                ? 'bg-slate-900 text-cyan-300 border-t border-x border-slate-800'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Download className="w-3.5 h-3.5" />
            1. Download Project ZIP
          </button>

          <button
            onClick={() => setActiveTab('terminal')}
            className={`flex items-center gap-1.5 px-3 py-2 rounded-t-lg font-medium transition cursor-pointer ${
              activeTab === 'terminal'
                ? 'bg-slate-900 text-cyan-300 border-t border-x border-slate-800'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Terminal className="w-3.5 h-3.5" />
            2. 1-Click Terminal Command
          </button>

          <button
            onClick={() => setActiveTab('drive')}
            className={`flex items-center gap-1.5 px-3 py-2 rounded-t-lg font-medium transition cursor-pointer ${
              activeTab === 'drive'
                ? 'bg-slate-900 text-cyan-300 border-t border-x border-slate-800'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <FolderSync className="w-3.5 h-3.5" />
            3. Sync via Google Drive
          </button>
        </div>

        {/* Content Body */}
        <div className="p-6 overflow-y-auto space-y-4 text-xs flex-1">
          {bridgeSendStatus && (
            <div className="p-3 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-300">
              {bridgeSendStatus}
            </div>
          )}

          {activeTab === 'zip' && (
            <div className="space-y-4">
              <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
                <h4 className="font-semibold text-slate-200 text-sm flex items-center gap-2">
                  <Download className="w-4 h-4 text-cyan-400" />
                  Instant Android Studio Package (ZIP)
                </h4>
                <p className="text-slate-400 leading-relaxed text-xs">
                  Downloads the complete, pre-configured Android Studio project package (`HermesDuplex-AndroidStudio.zip`) directly to your MacStudio's Downloads folder.
                </p>
                <div className="pt-2">
                  <button
                    onClick={handleDownloadZip}
                    disabled={isExportingZip}
                    className="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white font-semibold transition cursor-pointer shadow-md disabled:opacity-50 text-xs"
                  >
                    <Download className="w-4 h-4" />
                    {isExportingZip ? 'Packaging Files...' : 'Download HermesDuplex-AndroidStudio.zip'}
                  </button>
                </div>
              </div>

              <div className="p-4 rounded-xl bg-slate-950/60 border border-slate-800 text-slate-400 space-y-2">
                <span className="font-semibold text-slate-300">How to open on your MacStudio:</span>
                <ol className="list-decimal list-inside space-y-1 text-slate-400 font-sans">
                  <li>Unzip <code className="text-cyan-300">HermesDuplex-AndroidStudio.zip</code></li>
                  <li>Open <strong>Android Studio</strong> on your MacStudio.</li>
                  <li>Click <strong>Open</strong> and choose the <code className="text-cyan-300">HermesDuplex</code> folder.</li>
                  <li>Click the green <strong>Run</strong> button (Shift + F10) to build and deploy to your phone!</li>
                </ol>
              </div>
            </div>
          )}

          {activeTab === 'terminal' && (
            <div className="space-y-4">
              <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
                <div className="flex items-center justify-between">
                  <h4 className="font-semibold text-slate-200 text-sm flex items-center gap-2">
                    <Terminal className="w-4 h-4 text-cyan-400" />
                    MacStudio Terminal Command
                  </h4>
                  <button
                    onClick={handleCopyTerminalCommand}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 font-medium transition cursor-pointer"
                  >
                    {copiedScript ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                    {copiedScript ? 'Copied to Clipboard!' : 'Copy Command'}
                  </button>
                </div>
                <p className="text-slate-400 text-xs">
                  Paste this into your MacStudio Terminal. It creates the entire directory structure in <code className="text-cyan-300">~/AndroidStudioProjects/HermesDuplex</code>, writes all Gradle & Kotlin files, and launches Android Studio automatically:
                </p>

                <pre className="p-3 bg-slate-900 border border-slate-800 rounded-lg font-mono text-[11px] text-cyan-300 overflow-x-auto max-h-56 leading-relaxed">
                  {`mkdir -p ~/AndroidStudioProjects/HermesDuplex/app/src/main/java/org/hermes/duplex/{service,audio,network,security}
cd ~/AndroidStudioProjects/HermesDuplex
# ... writes settings.gradle.kts, build.gradle.kts, AndroidManifest.xml, MainActivity.kt ...
open -a "Android Studio" ~/AndroidStudioProjects/HermesDuplex`}
                </pre>
              </div>
            </div>
          )}

          {activeTab === 'drive' && (
            <div className="space-y-4">
              <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
                <h4 className="font-semibold text-slate-200 text-sm flex items-center gap-2">
                  <FolderSync className="w-4 h-4 text-emerald-400" />
                  Sync to Google Drive for MacStudio
                </h4>
                <p className="text-slate-400 leading-relaxed text-xs">
                  Uploads the complete Android Studio project zip directly into your connected Google Drive account (<code className="text-emerald-300">mattdaragon@gmail.com</code>). If you have Google Drive for Desktop installed on your MacStudio, it will automatically sync to your Mac folder.
                </p>
                <div className="pt-2">
                  <button
                    onClick={handleSaveZipToGoogleDrive}
                    className="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-semibold transition cursor-pointer shadow-md text-xs"
                  >
                    <FolderSync className="w-4 h-4" />
                    Upload ZIP to Google Drive
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
