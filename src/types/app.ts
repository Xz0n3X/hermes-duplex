export type DuplexState =
  | 'disconnected'
  | 'connecting'
  | 'idle'
  | 'listening'
  | 'streaming'
  | 'thinking'
  | 'speaking'
  | 'interrupted'
  | 'error_fail_closed';

export interface ChatTurn {
  id: string;
  sender: 'user' | 'server';
  text: string;
  timestamp: string;
  audioDurationMs?: number;
  latencyMs?: number;
  vadScore?: number;
  isStreaming?: boolean;
}

export interface BridgeConfig {
  serverUrl: string;
  authToken: string;
  codec: 'OPUS' | 'PCM_16BIT';
  sampleRate: number;
  frameSizeMs: number;
  heartbeatIntervalMs: number;
  vadSensitivity: number; // 0 to 1
  failClosedEnabled: boolean;
  useBuiltinSimulator: boolean;
}

export interface ServiceState {
  isActive: boolean;
  wakeLockHeld: boolean;
  notificationId: number;
  serviceType: string;
  uptimeSeconds: number;
  batteryDrainRatePercentPerHour: number;
}
