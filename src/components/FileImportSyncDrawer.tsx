import React, { useState } from 'react';
import {
  X,
  FileCode,
  FileText,
  Upload,
  Plus,
  FolderSearch,
  CheckCircle,
  AlertCircle,
  Copy,
  Download,
  Trash2,
  ExternalLink,
} from 'lucide-react';
import { HermesFile } from '../types';
import { GoogleDriveExplorer } from './GoogleDriveExplorer';
import { User } from 'firebase/auth';

interface FileImportSyncDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  files: HermesFile[];
  onAddOrUpdateFile: (file: HermesFile) => void;
  onDeleteFile: (fileId: string) => void;
  user: User | null;
  onFilesLoadedFromDrive: (files: HermesFile[]) => void;
}

export const FileImportSyncDrawer: React.FC<FileImportSyncDrawerProps> = ({
  isOpen,
  onClose,
  files,
  onAddOrUpdateFile,
  onDeleteFile,
  user,
  onFilesLoadedFromDrive,
}) => {
  const [activeTab, setActiveTab] = useState<'files' | 'drive' | 'paste'>('files');
  const [pasteFileName, setPasteFileName] = useState('ANDROID_1_APP_KOTLIN.txt');
  const [pasteContent, setPasteContent] = useState('');
  const [pasteCategory, setPasteCategory] = useState<HermesFile['category']>('code');
  const [successNotice, setSuccessNotice] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleSavePastedFile = (e: React.FormEvent) => {
    e.preventDefault();
    if (!pasteFileName.trim() || !pasteContent.trim()) return;

    const newFile: HermesFile = {
      id: 'custom-' + Date.now(),
      name: pasteFileName.trim(),
      category: pasteCategory,
      description: 'Imported by user',
      content: pasteContent,
      source: 'upload',
    };

    onAddOrUpdateFile(newFile);
    setSuccessNotice(`File "${newFile.name}" added to workspace!`);
    setPasteContent('');
    setTimeout(() => setSuccessNotice(null), 3000);
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const uploadedFiles = e.target.files;
    if (!uploadedFiles || uploadedFiles.length === 0) return;

    Array.from(uploadedFiles).forEach((f) => {
      const reader = new FileReader();
      reader.onload = (event) => {
        const text = event.target?.result as string;
        let category: HermesFile['category'] = 'code';
        const lower = f.name.toLowerCase();
        if (lower.includes('protocol')) category = 'contract';
        else if (lower.includes('server')) category = 'server';
        else if (lower.endsWith('.md')) category = 'doc';

        onAddOrUpdateFile({
          id: 'upload-' + Date.now() + '-' + Math.random().toString(36).substring(7),
          name: f.name,
          category,
          description: `Uploaded from local device (${f.type || 'text'})`,
          content: text,
          source: 'upload',
        });
      };
      reader.readAsText(f);
    });

    setSuccessNotice(`Imported ${uploadedFiles.length} file(s)!`);
    setTimeout(() => setSuccessNotice(null), 3000);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-xs select-none">
      <div className="w-full max-w-3xl bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
        {/* Header */}
        <div className="px-5 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-950/70">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-cyan-500/10 border border-cyan-500/20 flex items-center justify-center text-cyan-400">
              <FileCode className="w-5 h-5" />
            </div>
            <div>
              <h3 className="font-semibold text-slate-100 text-sm">
                Project Files & Android Source Sync
              </h3>
              <p className="text-xs text-slate-400 font-mono">
                {files.length} active workspace files • Google Drive & Local Import
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

        {/* Tab Switcher */}
        <div className="px-5 pt-3 bg-slate-950 border-b border-slate-800 flex items-center gap-2 text-xs">
          <button
            onClick={() => setActiveTab('files')}
            className={`flex items-center gap-2 px-3.5 py-2 rounded-t-lg font-medium transition cursor-pointer ${
              activeTab === 'files'
                ? 'bg-slate-900 text-cyan-300 border-t border-x border-slate-800'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <FileCode className="w-3.5 h-3.5" />
            Project Files ({files.length})
          </button>

          <button
            onClick={() => setActiveTab('drive')}
            className={`flex items-center gap-2 px-3.5 py-2 rounded-t-lg font-medium transition cursor-pointer ${
              activeTab === 'drive'
                ? 'bg-slate-900 text-emerald-300 border-t border-x border-slate-800'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <FolderSearch className="w-3.5 h-3.5 text-emerald-400" />
            Google Drive Importer
          </button>

          <button
            onClick={() => setActiveTab('paste')}
            className={`flex items-center gap-2 px-3.5 py-2 rounded-t-lg font-medium transition cursor-pointer ${
              activeTab === 'paste'
                ? 'bg-slate-900 text-purple-300 border-t border-x border-slate-800'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Upload className="w-3.5 h-3.5 text-purple-400" />
            Import / Paste Android File
          </button>
        </div>

        {/* Content Body */}
        <div className="p-5 overflow-y-auto space-y-4 flex-1 text-xs">
          {successNotice && (
            <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-300 flex items-center gap-2">
              <CheckCircle className="w-4 h-4 text-emerald-400 shrink-0" />
              <span>{successNotice}</span>
            </div>
          )}

          {/* TAB 1: ALL ACTIVE FILES */}
          {activeTab === 'files' && (
            <div className="space-y-3">
              <div className="flex items-center justify-between text-slate-400 pb-2 border-b border-slate-800">
                <span>Loaded Android & Documentation Files</span>
                <label className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium transition cursor-pointer">
                  <Upload className="w-3.5 h-3.5" />
                  Upload Local Files (.kt, .txt, .md)
                  <input
                    type="file"
                    multiple
                    accept=".kt,.txt,.md,.xml,.json,.kts"
                    onChange={handleFileUpload}
                    className="hidden"
                  />
                </label>
              </div>

              <div className="border border-slate-800 rounded-xl overflow-hidden divide-y divide-slate-800 bg-slate-950">
                {files.map((file) => (
                  <div
                    key={file.id}
                    className="p-3 flex items-center justify-between hover:bg-slate-900/60 transition group"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      {file.name.endsWith('.md') ? (
                        <FileText className="w-4 h-4 text-purple-400 shrink-0" />
                      ) : (
                        <FileCode className="w-4 h-4 text-cyan-400 shrink-0" />
                      )}
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="font-mono text-slate-200 truncate group-hover:text-cyan-300 transition">
                            {file.name}
                          </p>
                          <span className="text-[10px] px-1.5 py-0.2 rounded font-mono bg-slate-800 text-slate-400">
                            {file.source}
                          </span>
                        </div>
                        <p className="text-[11px] text-slate-500 truncate">{file.description}</p>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 shrink-0">
                      <span className="text-[10px] text-slate-600 font-mono">
                        {file.content.split('\n').length} lines
                      </span>
                      <button
                        onClick={() => onDeleteFile(file.id)}
                        className="p-1 text-slate-600 hover:text-rose-400 transition cursor-pointer"
                        title="Remove file"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* TAB 2: GOOGLE DRIVE SCANNER */}
          {activeTab === 'drive' && (
            <GoogleDriveExplorer
              user={user}
              onFilesLoaded={onFilesLoadedFromDrive}
              loadedFiles={files}
            />
          )}

          {/* TAB 3: PASTE CUSTOM FILE CONTENT */}
          {activeTab === 'paste' && (
            <form onSubmit={handleSavePastedFile} className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="text-slate-300 font-medium">File Name</label>
                  <input
                    type="text"
                    value={pasteFileName}
                    onChange={(e) => setPasteFileName(e.target.value)}
                    placeholder="e.g. ANDROID_1_APP_KOTLIN.txt"
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-slate-200 font-mono focus:outline-none focus:border-cyan-500"
                  />
                </div>

                <div className="space-y-1">
                  <label className="text-slate-300 font-medium">Category</label>
                  <select
                    value={pasteCategory}
                    onChange={(e) => setPasteCategory(e.target.value as HermesFile['category'])}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-slate-200 font-mono focus:outline-none"
                  >
                    <option value="code">Kotlin / Java Source</option>
                    <option value="contract">WebSocket Protocol</option>
                    <option value="server">Server Contract</option>
                    <option value="doc">Markdown / Documentation</option>
                  </select>
                </div>
              </div>

              <div className="space-y-1">
                <label className="text-slate-300 font-medium">File Content / Source Code</label>
                <textarea
                  value={pasteContent}
                  onChange={(e) => setPasteContent(e.target.value)}
                  placeholder="Paste contents of ANDROID_*.txt or Kotlin source code here..."
                  rows={10}
                  className="w-full bg-slate-950 border border-slate-800 rounded-xl p-3 text-slate-200 font-mono text-[11px] focus:outline-none focus:border-cyan-500 leading-relaxed"
                />
              </div>

              <button
                type="submit"
                disabled={!pasteFileName.trim() || !pasteContent.trim()}
                className="w-full py-2.5 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white font-semibold transition cursor-pointer disabled:opacity-40"
              >
                Add / Update File in Workspace
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  );
};
