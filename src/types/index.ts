export interface DriveFile {
  id: string;
  name: string;
  mimeType: string;
  size?: string;
  modifiedTime?: string;
  webViewLink?: string;
  iconLink?: string;
  content?: string;
  isLoadingContent?: boolean;
}

export interface HermesFile {
  id: string;
  name: string;
  category: 'contract' | 'code' | 'doc' | 'server';
  description: string;
  content: string;
  source: 'drive' | 'preset' | 'upload';
  driveFileId?: string;
}

export interface SecurityCheckItem {
  id: string;
  title: string;
  status: 'passed' | 'warning' | 'critical';
  details: string;
  codeSnippet?: string;
  recommendation: string;
}

export interface AudioThreadingItem {
  id: string;
  component: string;
  status: 'optimal' | 'warning' | 'alert';
  metric: string;
  details: string;
  recommendation: string;
}

export interface ProtocolMessageSample {
  type: string;
  direction: 'client_to_server' | 'server_to_client';
  description: string;
  payload: Record<string, any>;
}
