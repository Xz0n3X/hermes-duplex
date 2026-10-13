import React, { useState, useEffect } from 'react';
import {
  Folder,
  FileCode,
  FileText,
  Search,
  RefreshCw,
  ExternalLink,
  DownloadCloud,
  CheckCircle2,
  AlertTriangle,
  FolderSearch,
  Sparkles,
  ArrowRight,
  User as UserIcon,
  LogOut,
  ChevronRight,
} from 'lucide-react';
import { User } from 'firebase/auth';
import { DriveFile, HermesFile } from '../types';
import { searchDriveFiles, findHermesProjectFiles, fetchFileContent } from '../services/drive';
import { googleSignIn, logout, getAccessToken } from '../services/auth';

interface GoogleDriveExplorerProps {
  user: User | null;
  onFilesLoaded: (files: HermesFile[]) => void;
  loadedFiles: HermesFile[];
}

export const GoogleDriveExplorer: React.FC<GoogleDriveExplorerProps> = ({
  user,
  onFilesLoaded,
  loadedFiles,
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [driveFiles, setDriveFiles] = useState<DriveFile[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [isScanningHermes, setIsScanningHermes] = useState(false);
  const [loadingFileId, setLoadingFileId] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successNotice, setSuccessNotice] = useState<string | null>(null);
  const [isLoggingIn, setIsLoggingIn] = useState(false);

  // Auto-search hermes files when user is logged in
  useEffect(() => {
    if (user) {
      handleQuickScanHermes();
    }
  }, [user]);

  const handleLogin = async () => {
    setIsLoggingIn(true);
    setErrorMessage(null);
    try {
      const res = await googleSignIn();
      if (res) {
        setSuccessNotice('Connected to Google Drive successfully!');
        setTimeout(() => setSuccessNotice(null), 3500);
      }
    } catch (err: any) {
      console.error(err);
      setErrorMessage(err.message || 'Failed to authenticate with Google Drive.');
    } finally {
      setIsLoggingIn(false);
    }
  };

  const handleLogout = async () => {
    try {
      await logout();
      setDriveFiles([]);
      setSuccessNotice('Signed out of Google Drive.');
      setTimeout(() => setSuccessNotice(null), 3000);
    } catch (err: any) {
      setErrorMessage(err.message || 'Sign out failed');
    }
  };

  const handleSearch = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!user) return;
    setIsSearching(true);
    setErrorMessage(null);
    try {
      const res = await searchDriveFiles(searchQuery);
      setDriveFiles(res.files);
      if (res.files.length === 0) {
        setErrorMessage(`No files matching "${searchQuery}" were found in your Google Drive.`);
      }
    } catch (err: any) {
      setErrorMessage(err.message || 'Error searching Google Drive');
    } finally {
      setIsSearching(false);
    }
  };

  const handleQuickScanHermes = async () => {
    setIsScanningHermes(true);
    setErrorMessage(null);
    try {
      const files = await findHermesProjectFiles();
      setDriveFiles(files);
      if (files.length > 0) {
        setSuccessNotice(`Found ${files.length} project file(s) in your Google Drive!`);
        setTimeout(() => setSuccessNotice(null), 4000);
      } else {
        setErrorMessage(
          'No files matching "hermes-duplex" found automatically. Try searching your specific folder or file name above.'
        );
      }
    } catch (err: any) {
      setErrorMessage(err.message || 'Failed to locate Hermes project files in Drive.');
    } finally {
      setIsScanningHermes(false);
    }
  };

  const handleLoadSingleFile = async (file: DriveFile) => {
    setLoadingFileId(file.id);
    setErrorMessage(null);
    try {
      const content = await fetchFileContent(file.id, file.mimeType);
      
      // Determine category
      let category: HermesFile['category'] = 'code';
      const lower = file.name.toLowerCase();
      if (lower.includes('protocol')) category = 'contract';
      else if (lower.includes('server')) category = 'server';
      else if (lower.includes('prompt') || lower.includes('state') || lower.endsWith('.md')) category = 'doc';

      const hermesFile: HermesFile = {
        id: file.id,
        name: file.name,
        category,
        description: `Imported from Google Drive (${file.mimeType})`,
        content,
        source: 'drive',
        driveFileId: file.id,
      };

      // Add or replace
      const updated = loadedFiles.filter((f) => f.id !== file.id && f.name !== file.name);
      onFilesLoaded([...updated, hermesFile]);

      setSuccessNotice(`Loaded "${file.name}" into workspace!`);
      setTimeout(() => setSuccessNotice(null), 3000);
    } catch (err: any) {
      setErrorMessage(`Failed to download ${file.name}: ${err.message}`);
    } finally {
      setLoadingFileId(null);
    }
  };

  const handleImportAllFound = async () => {
    if (driveFiles.length === 0) return;
    setIsScanningHermes(true);
    setErrorMessage(null);

    const imported: HermesFile[] = [];
    const nonFolders = driveFiles.filter(
      (f) => f.mimeType !== 'application/vnd.google-apps.folder'
    );

    for (const f of nonFolders.slice(0, 10)) {
      try {
        const content = await fetchFileContent(f.id, f.mimeType);
        let category: HermesFile['category'] = 'code';
        const lower = f.name.toLowerCase();
        if (lower.includes('protocol')) category = 'contract';
        else if (lower.includes('server')) category = 'server';
        else if (lower.includes('.md')) category = 'doc';

        imported.push({
          id: f.id,
          name: f.name,
          category,
          description: `Imported from Google Drive`,
          content,
          source: 'drive',
          driveFileId: f.id,
        });
      } catch (err) {
        console.warn(`Could not import ${f.name}:`, err);
      }
    }

    if (imported.length > 0) {
      // Merge
      const existingNames = new Set(imported.map((i) => i.name));
      const remaining = loadedFiles.filter((f) => !existingNames.has(f.name));
      onFilesLoaded([...remaining, ...imported]);
      setSuccessNotice(`Successfully loaded ${imported.length} file(s) into Hermes Studio!`);
      setTimeout(() => setSuccessNotice(null), 4000);
    } else {
      setErrorMessage('Could not read file contents. Check permissions or file format.');
    }
    setIsScanningHermes(false);
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-lg">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-slate-800">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center text-emerald-400">
            <FolderSearch className="w-5 h-5" />
          </div>
          <div>
            <h2 className="font-semibold text-slate-100 flex items-center gap-2">
              Google Drive Workspace
              <span className="text-xs px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-mono">
                API v3 Connected
              </span>
            </h2>
            <p className="text-xs text-slate-400">
              Browse your Drive folders and pull files directly into the Android code analyzer
            </p>
          </div>
        </div>

        {/* User Auth Section */}
        <div>
          {!user ? (
            <button
              onClick={handleLogin}
              disabled={isLoggingIn}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-white hover:bg-slate-100 text-slate-800 text-sm font-medium transition shadow-sm border border-slate-300 disabled:opacity-50 cursor-pointer"
            >
              <svg className="w-4 h-4" viewBox="0 0 48 48">
                <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
                <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
                <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
                <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
              </svg>
              {isLoggingIn ? 'Connecting...' : 'Sign in with Google'}
            </button>
          ) : (
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-800/80 border border-slate-700/60 text-xs text-slate-300">
                {user.photoURL ? (
                  <img src={user.photoURL} alt="Avatar" className="w-5 h-5 rounded-full" />
                ) : (
                  <UserIcon className="w-4 h-4 text-slate-400" />
                )}
                <span className="font-medium max-w-[150px] truncate">{user.displayName || user.email}</span>
              </div>
              <button
                onClick={handleLogout}
                title="Disconnect Google Drive"
                className="p-1.5 rounded-lg text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 transition"
              >
                <LogOut className="w-4 h-4" />
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Status alerts */}
      {errorMessage && (
        <div className="mt-4 p-3 rounded-lg bg-rose-500/10 border border-rose-500/20 text-rose-300 text-xs flex items-center gap-2">
          <AlertTriangle className="w-4 h-4 shrink-0 text-rose-400" />
          <span>{errorMessage}</span>
        </div>
      )}

      {successNotice && (
        <div className="mt-4 p-3 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-300 text-xs flex items-center gap-2">
          <CheckCircle2 className="w-4 h-4 shrink-0 text-emerald-400" />
          <span>{successNotice}</span>
        </div>
      )}

      {/* Drive actions bar */}
      <div className="mt-4 flex flex-col md:flex-row items-stretch md:items-center gap-3">
        <form onSubmit={handleSearch} className="flex-1 relative">
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search Drive for 'hermes-duplex', 'PROTOCOL', 'ANDROID_'..."
            disabled={!user}
            className="w-full bg-slate-950 border border-slate-800 rounded-lg pl-9 pr-24 py-2 text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:border-cyan-500 transition disabled:opacity-50"
          />
          <Search className="w-4 h-4 text-slate-500 absolute left-3 top-2.5" />
          <button
            type="submit"
            disabled={!user || isSearching}
            className="absolute right-1.5 top-1.5 px-2.5 py-1 rounded bg-slate-800 hover:bg-slate-700 text-xs text-slate-300 transition disabled:opacity-50"
          >
            {isSearching ? 'Searching...' : 'Search'}
          </button>
        </form>

        <div className="flex items-center gap-2">
          <button
            onClick={handleQuickScanHermes}
            disabled={!user || isScanningHermes}
            className="flex-1 md:flex-none inline-flex items-center justify-center gap-2 px-3 py-2 rounded-lg bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-medium transition shadow-sm disabled:opacity-50 cursor-pointer"
          >
            {isScanningHermes ? (
              <RefreshCw className="w-3.5 h-3.5 animate-spin" />
            ) : (
              <Sparkles className="w-3.5 h-3.5 text-cyan-200" />
            )}
            Quick Find Hermes Files
          </button>

          {driveFiles.length > 0 && (
            <button
              onClick={handleImportAllFound}
              disabled={isScanningHermes}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg bg-emerald-600/90 hover:bg-emerald-500 text-white text-xs font-medium transition disabled:opacity-50 cursor-pointer"
              title="Import all found files directly into the workspace"
            >
              <DownloadCloud className="w-3.5 h-3.5" />
              Load All Found
            </button>
          )}
        </div>
      </div>

      {/* Drive File Results */}
      <div className="mt-4">
        {!user ? (
          <div className="text-center py-8 px-4 border border-dashed border-slate-800 rounded-lg bg-slate-950/40">
            <FolderSearch className="w-10 h-10 text-slate-600 mx-auto mb-3" />
            <h3 className="text-sm font-medium text-slate-300">Google Drive Not Connected</h3>
            <p className="text-xs text-slate-500 max-w-md mx-auto mt-1 mb-4">
              Sign in with your Google account to access your Google Drive folder and pull Hermes Duplex source files and protocols directly into the editor without manually copy-pasting.
            </p>
            <button
              onClick={handleLogin}
              disabled={isLoggingIn}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-medium transition cursor-pointer"
            >
              Connect Google Drive Account
            </button>
          </div>
        ) : driveFiles.length === 0 ? (
          <div className="text-center py-8 px-4 border border-dashed border-slate-800 rounded-lg bg-slate-950/40">
            <p className="text-xs text-slate-400">
              {isScanningHermes || isSearching
                ? 'Scanning Google Drive for files...'
                : 'Click "Quick Find Hermes Files" or search above to browse files in your Drive.'}
            </p>
          </div>
        ) : (
          <div className="border border-slate-800 rounded-lg overflow-hidden bg-slate-950/60 max-h-64 overflow-y-auto divide-y divide-slate-800/80">
            {driveFiles.map((file) => {
              const isFolder = file.mimeType === 'application/vnd.google-apps.folder';
              const isLoaded = loadedFiles.some((f) => f.name === file.name || f.driveFileId === file.id);
              const isLoading = loadingFileId === file.id;

              return (
                <div
                  key={file.id}
                  className="flex items-center justify-between px-3 py-2.5 hover:bg-slate-900/60 transition group text-xs"
                >
                  <div className="flex items-center gap-2.5 min-w-0 pr-2">
                    {isFolder ? (
                      <Folder className="w-4 h-4 text-amber-400 shrink-0" />
                    ) : file.name.endsWith('.md') ? (
                      <FileText className="w-4 h-4 text-purple-400 shrink-0" />
                    ) : (
                      <FileCode className="w-4 h-4 text-cyan-400 shrink-0" />
                    )}

                    <div className="min-w-0">
                      <p className="font-mono text-slate-200 truncate group-hover:text-cyan-300 transition">
                        {file.name}
                      </p>
                      <p className="text-[10px] text-slate-500 truncate">
                        {isFolder ? 'Folder' : file.size ? `${Math.round(parseInt(file.size) / 1024)} KB` : 'Document'}
                        {file.modifiedTime && ` • Modified ${new Date(file.modifiedTime).toLocaleDateString()}`}
                      </p>
                    </div>
                  </div>

                  <div className="flex items-center gap-2 shrink-0">
                    {file.webViewLink && (
                      <a
                        href={file.webViewLink}
                        target="_blank"
                        rel="noreferrer"
                        className="p-1 text-slate-500 hover:text-slate-300 transition"
                        title="Open in Google Drive"
                      >
                        <ExternalLink className="w-3.5 h-3.5" />
                      </a>
                    )}

                    {!isFolder && (
                      <button
                        onClick={() => handleLoadSingleFile(file)}
                        disabled={isLoading}
                        className={`inline-flex items-center gap-1 px-2.5 py-1 rounded text-[11px] font-medium transition cursor-pointer ${
                          isLoaded
                            ? 'bg-slate-800 text-emerald-400 border border-emerald-500/30 hover:bg-slate-700'
                            : 'bg-cyan-600/20 text-cyan-300 border border-cyan-500/30 hover:bg-cyan-600/40'
                        }`}
                      >
                        {isLoading ? (
                          <>
                            <RefreshCw className="w-3 h-3 animate-spin" />
                            Loading...
                          </>
                        ) : isLoaded ? (
                          <>
                            <CheckCircle2 className="w-3 h-3 text-emerald-400" />
                            Reload
                          </>
                        ) : (
                          <>
                            <DownloadCloud className="w-3 h-3" />
                            Load into Studio
                          </>
                        )}
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};
