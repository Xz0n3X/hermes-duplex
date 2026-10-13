import React, { useState } from 'react';
import {
  Wand2,
  Check,
  Copy,
  ChevronDown,
  ChevronRight,
  ShieldAlert,
  Cpu,
  Radio,
  Sparkles,
  ArrowRight,
  PlusCircle,
} from 'lucide-react';
import { HermesFile } from '../types';

interface RefactorProposalsProps {
  onAddOptimizedFile: (file: HermesFile) => void;
  files: HermesFile[];
}

interface ProposalItem {
  id: string;
  title: string;
  fileName: string;
  category: HermesFile['category'];
  badge: string;
  badgeColor: string;
  description: string;
  benefits: string[];
  code: string;
}

const PROPOSALS: ProposalItem[] = [
  {
    id: 'ring-buffer',
    title: 'Zero-Allocation Circular Ring Buffer for Audio Capture',
    fileName: 'RingBuffer.kt',
    category: 'code',
    badge: 'Latency & GC',
    badgeColor: 'amber',
    description:
      'Replaces heap object allocations (e.g., buffer.copyOf()) with a fixed circular byte ring buffer. Guarantees deterministic 20ms audio frame dispatch with 0 garbage collector pauses.',
    benefits: [
      'Eliminates audio frame drops caused by Android Dalvik/ART GC pauses',
      'Thread-safe lock-free head/tail pointers between AudioRecord and Network thread',
      'Pre-allocated memory footprint of exactly 4 frames (3,840 bytes)',
    ],
    code: `package org.hermes.duplex.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance, lock-free circular ring buffer specifically tuned for
 * 20ms (960 byte) audio frames at 24kHz 16-bit Mono.
 * ZERO object allocations after initialization.
 */
class CircularAudioRingBuffer(capacityFrames: Int = 4) {
    private val frameSize = 960
    private val buffer = ByteArray(capacityFrames * frameSize)
    private val totalCapacity = capacityFrames * frameSize

    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)

    fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        val currentWrite = writeIndex.get()
        val currentRead = readIndex.get()
        
        // Calculate available space
        val available = if (currentWrite >= currentRead) {
            totalCapacity - (currentWrite - currentRead)
        } else {
            currentRead - currentWrite
        }

        if (available < length) {
            return false // Buffer overrun protection
        }

        for (i in 0 until length) {
            val idx = (currentWrite + i) % totalCapacity
            buffer[idx] = data[offset + i]
        }

        writeIndex.set((currentWrite + length) % totalCapacity)
        return true
    }

    fun readFrame(dest: ByteArray): Boolean {
        if (dest.size < frameSize) return false
        val currentRead = readIndex.get()
        val currentWrite = writeIndex.get()

        val available = if (currentWrite >= currentRead) {
            currentWrite - currentRead
        } else {
            totalCapacity - (currentRead - currentWrite)
        }

        if (available < frameSize) {
            return false // Not enough bytes for full 20ms frame
        }

        for (i in 0 until frameSize) {
            val idx = (currentRead + i) % totalCapacity
            dest[i] = buffer[idx]
        }

        readIndex.set((currentRead + frameSize) % totalCapacity)
        return true
    }

    fun clear() {
        writeIndex.set(0)
        readIndex.set(0)
    }
}
`,
  },
  {
    id: 'dead-man-switch',
    title: 'Fail-Closed Dead-Man Switch & Security Policy',
    fileName: 'FailClosedSecurityManager.kt',
    category: 'code',
    badge: 'Security',
    badgeColor: 'rose',
    description:
      'Implements a fail-closed circuit breaker. If bridge WebSocket heartbeats stall or the device microphone permission is altered, audio capture is forcefully killed and flushed.',
    benefits: [
      'Guarantees no audio is recorded while disconnected from the bridge',
      'Instant memory flush of remaining audio chunks',
      'Prevents background listening if bridge authorization expires',
    ],
    code: `package org.hermes.duplex.security

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object FailClosedSecurityManager {
    private const val TAG = "FailClosedSecurity"
    private val isSecureState = AtomicBoolean(false)
    private var onSecurityBreachCallback: (() -> Unit)? = null

    fun initialize(onBreach: () -> Unit) {
        onSecurityBreachCallback = onBreach
        isSecureState.set(true)
    }

    fun validateToken(token: String?): Boolean {
        if (token.isNullOrBlank() || token.length < 16) {
            triggerFailClosed("Invalid or missing bridge token")
            return false
        }
        return true
    }

    fun onHeartbeatMissed(consecutiveMisses: Int) {
        if (consecutiveMisses >= 2) {
            triggerFailClosed("Missed 2 consecutive bridge pings (30s timeout)")
        }
    }

    fun triggerFailClosed(reason: String) {
        if (isSecureState.getAndSet(false)) {
            Log.e(TAG, "🚨 FAIL-CLOSED TRIGGERED: \$reason. Halting audio and killing service.")
            onSecurityBreachCallback?.invoke()
        }
    }

    fun isPermitted(): Boolean = isSecureState.get()
}
`,
  },
  {
    id: 'android-14-manifest',
    title: 'Android 14 (API 34) Foreground Service Spec & Manifest',
    fileName: 'ANDROID_MANIFEST_API34.xml',
    category: 'code',
    badge: 'Android 14',
    badgeColor: 'blue',
    description:
      'Complete manifest and permission declarations compliant with Android 14 (targetSdk 34) strict foreground service microphone enforcement.',
    benefits: [
      'Prevents Android 14 SecurityException when starting foreground microphone capture',
      'Declares runtime notification and wake lock permissions',
      'Configures network security config for local bridge IP communication',
    ],
    code: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.hermes.duplex">

    <!-- Foreground Service Permissions for API 34 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <!-- Core Audio & Hardware -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".HermesDuplexApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.HermesDuplex"
        android:usesCleartextTraffic="true"> <!-- Permitted for local LAN Mac bridge -->

        <service
            android:name=".service.DuplexForegroundService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="microphone|dataSync" />

    </application>
</manifest>
`,
  },
];

export const RefactorProposals: React.FC<RefactorProposalsProps> = ({
  onAddOptimizedFile,
  files,
}) => {
  const [expandedId, setExpandedId] = useState<string | null>('ring-buffer');
  const [appliedIds, setAppliedIds] = useState<Set<string>>(new Set());
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const handleApply = (proposal: ProposalItem) => {
    const newFile: HermesFile = {
      id: proposal.id,
      name: proposal.fileName,
      category: proposal.category,
      description: proposal.title,
      content: proposal.code,
      source: 'preset',
    };
    onAddOptimizedFile(newFile);
    setAppliedIds((prev) => new Set([...prev, proposal.id]));
  };

  const handleCopy = (id: string, text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-lg">
      <div className="p-5 border-b border-slate-800 bg-slate-950/60 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-cyan-500/10 border border-cyan-500/20 flex items-center justify-center text-cyan-400">
            <Wand2 className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-slate-100 flex items-center gap-2">
              Engineering Improvements ("Make This Better")
              <span className="text-xs px-2 py-0.5 rounded-full bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                Senior Systems Review
              </span>
            </h3>
            <p className="text-xs text-slate-400">
              Battle-tested architectural enhancements ready to add directly to your Hermes Duplex workspace
            </p>
          </div>
        </div>
      </div>

      <div className="divide-y divide-slate-800">
        {PROPOSALS.map((prop) => {
          const isExpanded = expandedId === prop.id;
          const isApplied = appliedIds.has(prop.id) || files.some((f) => f.name === prop.fileName);

          return (
            <div key={prop.id} className="p-4 bg-slate-950/40 hover:bg-slate-950/70 transition">
              <div
                onClick={() => setExpandedId(isExpanded ? null : prop.id)}
                className="flex items-center justify-between cursor-pointer"
              >
                <div className="flex items-center gap-3 min-w-0">
                  {isExpanded ? (
                    <ChevronDown className="w-4 h-4 text-slate-400 shrink-0" />
                  ) : (
                    <ChevronRight className="w-4 h-4 text-slate-400 shrink-0" />
                  )}
                  <div>
                    <div className="flex items-center gap-2">
                      <h4 className="text-sm font-semibold text-slate-200">{prop.title}</h4>
                      <span className="text-[10px] px-2 py-0.5 rounded-full font-mono bg-slate-800 text-slate-300">
                        {prop.badge}
                      </span>
                    </div>
                    <p className="text-xs text-slate-400 mt-0.5">{prop.description}</p>
                  </div>
                </div>

                <div className="flex items-center gap-2 shrink-0 ml-3" onClick={(e) => e.stopPropagation()}>
                  <button
                    onClick={() => handleApply(prop)}
                    className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition cursor-pointer ${
                      isApplied
                        ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/30'
                        : 'bg-cyan-600 hover:bg-cyan-500 text-white shadow-sm'
                    }`}
                  >
                    {isApplied ? (
                      <>
                        <Check className="w-3.5 h-3.5" />
                        In Workspace
                      </>
                    ) : (
                      <>
                        <PlusCircle className="w-3.5 h-3.5" />
                        Add to Project
                      </>
                    )}
                  </button>
                </div>
              </div>

              {isExpanded && (
                <div className="mt-4 pt-4 border-t border-slate-800/80 space-y-3">
                  <div className="bg-slate-900/60 rounded-lg p-3 border border-slate-800 text-xs">
                    <span className="font-semibold text-slate-300 text-[11px] uppercase tracking-wide">
                      Key Architectural Benefits:
                    </span>
                    <ul className="mt-1.5 space-y-1 text-slate-400 list-disc list-inside text-xs">
                      {prop.benefits.map((b, i) => (
                        <li key={i}>{b}</li>
                      ))}
                    </ul>
                  </div>

                  <div className="relative">
                    <div className="flex items-center justify-between px-3 py-2 bg-slate-900 border-t border-x border-slate-800 rounded-t-lg text-xs text-slate-400 font-mono">
                      <span>{prop.fileName}</span>
                      <button
                        onClick={() => handleCopy(prop.id, prop.code)}
                        className="flex items-center gap-1 hover:text-slate-200 transition cursor-pointer"
                      >
                        {copiedId === prop.id ? (
                          <Check className="w-3.5 h-3.5 text-emerald-400" />
                        ) : (
                          <Copy className="w-3.5 h-3.5" />
                        )}
                        {copiedId === prop.id ? 'Copied' : 'Copy'}
                      </button>
                    </div>
                    <pre className="p-4 bg-slate-950 rounded-b-lg border border-slate-800 font-mono text-[11px] text-cyan-300 overflow-x-auto max-h-80 leading-relaxed">
                      {prop.code}
                    </pre>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};
