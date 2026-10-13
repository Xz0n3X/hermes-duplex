import React, { useState, useRef, useEffect } from 'react';
import {
  Send,
  Volume2,
  Mic,
  Trash2,
  DownloadCloud,
  CheckCircle2,
  User,
  Radio,
  Clock,
  Sparkles,
} from 'lucide-react';
import { ChatTurn } from '../types/app';

interface ChatTranscriptScreenProps {
  turns: ChatTurn[];
  onSendMessage: (text: string) => void;
  onPlayTurnAudio: (text: string) => void;
  onClearTranscript: () => void;
  onSaveToDrive?: () => void;
  isSavingToDrive?: boolean;
}

export const ChatTranscriptScreen: React.FC<ChatTranscriptScreenProps> = ({
  turns,
  onSendMessage,
  onPlayTurnAudio,
  onClearTranscript,
  onSaveToDrive,
  isSavingToDrive,
}) => {
  const [inputText, setInputText] = useState('');
  const messagesEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [turns]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (inputText.trim()) {
      onSendMessage(inputText.trim());
      setInputText('');
    }
  };

  return (
    <div className="flex-1 flex flex-col justify-between max-w-xl mx-auto w-full h-full p-4">
      {/* Transcript Toolbar */}
      <div className="flex items-center justify-between pb-3 border-b border-slate-800 text-xs text-slate-400">
        <div className="flex items-center gap-2">
          <span className="font-semibold text-slate-200">Duplex Transcript</span>
          <span className="text-[10px] px-2 py-0.5 rounded-full bg-slate-800 text-slate-300 font-mono">
            {turns.length} turns
          </span>
        </div>

        <div className="flex items-center gap-2">
          {onSaveToDrive && (
            <button
              onClick={onSaveToDrive}
              disabled={isSavingToDrive || turns.length === 0}
              className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-emerald-600/20 hover:bg-emerald-600/30 text-emerald-300 text-xs font-medium transition disabled:opacity-40 cursor-pointer"
              title="Save transcript to connected Google Drive"
            >
              <DownloadCloud className="w-3.5 h-3.5" />
              {isSavingToDrive ? 'Saving...' : 'Sync to Drive'}
            </button>
          )}

          <button
            onClick={onClearTranscript}
            disabled={turns.length === 0}
            className="p-1 text-slate-500 hover:text-rose-400 transition cursor-pointer disabled:opacity-30"
            title="Clear transcript"
          >
            <Trash2 className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Messages Scroll Area */}
      <div className="flex-1 overflow-y-auto py-4 space-y-4 pr-1">
        {turns.length === 0 ? (
          <div className="h-full flex flex-col items-center justify-center text-center p-6 text-slate-500 text-xs">
            <Radio className="w-8 h-8 text-slate-700 mb-2 animate-pulse" />
            <p className="font-medium text-slate-400">No voice turns recorded yet.</p>
            <p className="text-[11px] text-slate-600 mt-1">
              Switch to the Voice tab or type a message below to begin the duplex stream.
            </p>
          </div>
        ) : (
          turns.map((turn) => {
            const isUser = turn.sender === 'user';
            return (
              <div
                key={turn.id}
                className={`flex flex-col ${isUser ? 'items-end' : 'items-start'} space-y-1`}
              >
                <div className="flex items-center gap-1.5 text-[10px] text-slate-500 px-1 font-mono">
                  <span>{isUser ? 'You' : 'Hermes Mac Bridge'}</span>
                  <span>·</span>
                  <span>{turn.timestamp}</span>
                  {turn.latencyMs && (
                    <>
                      <span>·</span>
                      <span className="text-cyan-400 font-semibold">{turn.latencyMs}ms</span>
                    </>
                  )}
                </div>

                <div
                  className={`max-w-[85%] rounded-2xl px-4 py-3 text-xs leading-relaxed ${
                    isUser
                      ? 'bg-cyan-600 text-white rounded-br-xs shadow-md'
                      : 'bg-slate-900 border border-slate-800 text-slate-200 rounded-bl-xs shadow-sm'
                  }`}
                >
                  <p>{turn.text}</p>

                  {!isUser && (
                    <div className="mt-2 pt-2 border-t border-slate-800/80 flex items-center justify-between">
                      <span className="text-[10px] text-slate-500 font-mono">Opus Audio 24kHz</span>
                      <button
                        onClick={() => onPlayTurnAudio(turn.text)}
                        className="inline-flex items-center gap-1 text-[10px] text-cyan-400 hover:text-cyan-300 font-medium transition cursor-pointer"
                      >
                        <Volume2 className="w-3 h-3" />
                        Replay Audio
                      </button>
                    </div>
                  )}
                </div>
              </div>
            );
          })
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Message Input Form */}
      <form onSubmit={handleSubmit} className="pt-2">
        <div className="relative flex items-center">
          <input
            type="text"
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            placeholder="Type a message or prompt to the bridge..."
            className="w-full bg-slate-900 border border-slate-800 rounded-2xl pl-4 pr-12 py-3 text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:border-cyan-500 transition shadow-inner"
          />
          <button
            type="submit"
            disabled={!inputText.trim()}
            className="absolute right-2 p-2 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white transition disabled:opacity-40 cursor-pointer shadow-sm"
          >
            <Send className="w-3.5 h-3.5" />
          </button>
        </div>
      </form>
    </div>
  );
};
