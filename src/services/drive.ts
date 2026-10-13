import { getAccessToken } from './auth';
import { DriveFile } from '../types';

const DRIVE_API_BASE = 'https://www.googleapis.com/drive/v3';

export async function searchDriveFiles(
  query: string = '',
  folderId?: string,
  pageSize: number = 25
): Promise<{ files: DriveFile[]; nextPageToken?: string }> {
  const token = await getAccessToken();
  if (!token) {
    throw new Error('Not authenticated with Google Drive');
  }

  const queryParts: string[] = ['trashed = false'];

  if (folderId && folderId !== 'root') {
    queryParts.push(`'${folderId}' in parents`);
  }

  if (query.trim()) {
    const escaped = query.replace(/'/g, "\\'");
    queryParts.push(`(name contains '${escaped}' or fullText contains '${escaped}')`);
  }

  const q = queryParts.join(' and ');
  const fields = 'nextPageToken, files(id, name, mimeType, size, modifiedTime, webViewLink, iconLink)';

  const url = `${DRIVE_API_BASE}/files?q=${encodeURIComponent(q)}&fields=${encodeURIComponent(
    fields
  )}&pageSize=${pageSize}&orderBy=folder,modifiedTime desc`;

  const res = await fetch(url, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
    },
  });

  if (!res.ok) {
    const errorText = await res.text();
    let errorMsg = `Google Drive API error (${res.status})`;
    try {
      const parsed = JSON.parse(errorText);
      if (parsed.error?.message) {
        errorMsg = parsed.error.message;
      }
    } catch {}
    throw new Error(errorMsg);
  }

  const data = await res.json();
  return {
    files: data.files || [],
    nextPageToken: data.nextPageToken,
  };
}

export async function findHermesProjectFiles(): Promise<DriveFile[]> {
  const token = await getAccessToken();
  if (!token) {
    throw new Error('Not authenticated with Google Drive');
  }

  // Broad search targeting Hermes Duplex project naming patterns
  const q =
    "trashed = false and (name contains 'hermes' or name contains 'duplex' or name contains 'GEMINI_PROMPT' or name contains 'PROTOCOL' or name contains 'CURRENT_STATE' or name contains 'SERVER_CONTRACT' or name contains 'ANDROID_' or name contains 'duplex_')";

  const fields = 'files(id, name, mimeType, size, modifiedTime, webViewLink, iconLink)';
  const url = `${DRIVE_API_BASE}/files?q=${encodeURIComponent(
    q
  )}&fields=${encodeURIComponent(fields)}&pageSize=50&orderBy=folder,name`;

  const res = await fetch(url, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
    },
  });

  if (!res.ok) {
    const errorText = await res.text();
    throw new Error(`Failed to query Hermes files: ${errorText}`);
  }

  const data = await res.json();
  return data.files || [];
}

export async function fetchFileContent(
  fileId: string,
  mimeType: string = 'text/plain'
): Promise<string> {
  const token = await getAccessToken();
  if (!token) {
    throw new Error('Not authenticated with Google Drive');
  }

  let fetchUrl = `${DRIVE_API_BASE}/files/${fileId}?alt=media`;

  // If it's a native Google Doc, export it as plain text
  if (mimeType === 'application/vnd.google-apps.document') {
    fetchUrl = `${DRIVE_API_BASE}/files/${fileId}/export?mimeType=text/plain`;
  }

  const res = await fetch(fetchUrl, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });

  if (!res.ok) {
    const errorText = await res.text();
    throw new Error(`Failed to read file content (${res.status}): ${errorText}`);
  }

  return await res.text();
}
