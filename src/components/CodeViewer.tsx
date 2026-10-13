import React, { useState } from 'react';
import {
  FileCode,
  FileText,
  Copy,
  Check,
  Download,
  Edit3,
  RotateCcw,
  Sparkles,
  Info,
} from 'lucide-react';
import { HermesFile } from '../types';

interface CodeViewerProps {
  files: HermesFile[];
  activeFileId: string;
  onSelectFile: (fileId: string) => void;
  onUpdateFileContent: (fileId: string, newContent: string) => void;
}

export const CodeViewer: React.FC<CodeViewerProps> = ({
  files,
  activeFileId,
  onSelectFile,
  onUpdateFileContent,
}) => {
  const [copied, setCopied] = useState(false);
  const [isEditing, setIsEditing] = useState(false);

  const currentFile = files.find((f) => f.id === activeFileId) || files[0];

  const handleCopy = () => {
    if (!currentFile) return;
    navigator.clipboard.writeText(currentFile.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownload = () => {
    if (!currentFile) return;
    const blob = new Blob([currentFile.content], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = currentFile.name;
    a.click();
    URL.revokeObjectURL(url);
  };

  if (!currentFile) {
    return (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-8 text-center text-slate-500 text-sm">
        No files loaded in the workspace.
      </div>
    );
  }

  const lines = currentFile.content.split('\n');

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-lg flex flex-col">
      {/* File Tabs Header */}
      <div className="border-b border-slate-800 bg-slate-950/80 px-3 pt-3 flex items-center gap-1.5 overflow-x-auto">
        {files.map((file) => {
          const isActive = file.id === currentFile.id;
          return (
            <button
              key={file.id}
              onClick={() => onSelectFile(file.id)}
              className={`flex items-center gap-2 px-3.5 py-2 text-xs font-mono rounded-t-lg transition border-t border-x cursor-pointer whitespace-nowrap ${
                isActive
                  ? 'bg-slate-900 text-cyan-300 border-slate-800 border-b-transparent font-medium shadow-sm'
                  : 'text-slate-400 hover:text-slate-200 border-transparent hover:bg-slate-900/40'
              }`}
            >
              {file.name.endsWith('.md') ? (
                <FileText className="w-3.5 h-3.5 text-purple-400" />
              ) : (
                <FileCode className="w-3.5 h-3.5 text-cyan-400" />
              )}
              <span>{file.name}</span>
              {file.source === 'drive' && (
                <span className="text-[9px] px-1 py-0.2 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                  Drive
                </span>
              )}
            </button>
          );
        })}
      </div>

      {/* File Action & Description Sub-bar */}
      <div className="p-3 border-b border-slate-800/80 bg-slate-900/90 flex flex-col sm:flex-row sm:items-center justify-between gap-2 text-xs">
        <div className="flex items-center gap-2 text-slate-400 truncate">
          <Info className="w-3.5 h-3.5 text-slate-500 shrink-0" />
          <span className="truncate">{currentFile.description}</span>
          <span className="text-slate-600">•</span>
          <span className="text-slate-500 font-mono text-[11px]">{lines.length} lines</span>
        </div>

        <div className="flex items-center gap-2 shrink-0">
          <button
            onClick={() => setIsEditing(!isEditing)}
            className={`inline-flex items-center gap-1 px-2.5 py-1 rounded text-xs transition cursor-pointer ${
              isEditing
                ? 'bg-amber-500/20 text-amber-300 border border-amber-500/30'
                : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
            }`}
          >
            <Edit3 className="w-3 h-3" />
            {isEditing ? 'Editing Mode' : 'Edit File'}
          </button>

          <button
            onClick={handleCopy}
            className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-slate-800 text-slate-300 hover:bg-slate-700 text-xs transition cursor-pointer"
          >
            {copied ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
            {copied ? 'Copied' : 'Copy'}
          </button>

          <button
            onClick={handleDownload}
            className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-slate-800 text-slate-300 hover:bg-slate-700 text-xs transition cursor-pointer"
          >
            <Download className="w-3 h-3" />
            Export
          </button>
        </div>
      </div>

      {/* Code Editor Body */}
      <div className="relative font-mono text-xs bg-slate-950 flex-1 min-h-[380px] max-h-[550px] overflow-auto">
        {isEditing ? (
          <textarea
            value={currentFile.content}
            onChange={(e) => onUpdateFileContent(currentFile.id, e.target.value)}
            className="w-full h-full min-h-[380px] p-4 bg-transparent text-slate-200 resize-none font-mono text-xs focus:outline-none leading-relaxed"
            spellCheck={false}
          />
        ) : (
          <div className="p-4 flex">
            {/* Line numbers */}
            <div className="pr-4 mr-4 select-none text-right text-slate-600 border-r border-slate-800/80 font-mono text-[11px] leading-relaxed">
              {lines.map((_, i) => (
                <div key={i}>{i + 1}</div>
              ))}
            </div>

            {/* Code text */}
            <pre className="text-slate-300 flex-1 overflow-x-auto whitespace-pre font-mono text-[11px] leading-relaxed">
              {currentFile.content}
            </pre>
          </div>
        )}
      </div>
    </div>
  );
};
