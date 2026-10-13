import React, { useState } from 'react';
import {
  X,
  Github,
  Key,
  Shield,
  Copy,
  Check,
  ExternalLink,
  UploadCloud,
  CheckCircle2,
  AlertTriangle,
  RefreshCw,
  Terminal,
  Server,
  Code2,
} from 'lucide-react';
import { GitHubClient, GitHubUser, GitHubRepo } from '../services/github';

interface GitHubConnectModalProps {
  isOpen: boolean;
  onClose: () => void;
  allProjectFiles: { path: string; content: string }[];
}

export const SSH_PUBLIC_KEY =
  'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA48Hnc2ys7gzgnjJWL2rvFZZYsoGy9KQzsV+HlgxmA+ mattdaragon@gmail.com';

export const GitHubConnectModal: React.FC<GitHubConnectModalProps> = ({
  isOpen,
  onClose,
  allProjectFiles,
}) => {
  const [activeTab, setActiveTab] = useState<'token' | 'ssh'>('token');
  const [token, setToken] = useState('');
  const [repoOwner, setRepoOwner] = useState('Xz0n3X');
  const [repoName, setRepoName] = useState('hermes-duplex');
  const [isPrivate, setIsPrivate] = useState(true);

  // States
  const [isVerifying, setIsVerifying] = useState(false);
  const [isPushing, setIsPushing] = useState(false);
  const [isCreatingRepo, setIsCreatingRepo] = useState(false);
  const [verifiedUser, setVerifiedUser] = useState<GitHubUser | null>(null);
  const [existingRepo, setExistingRepo] = useState<GitHubRepo | null>(null);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [pushedCommitSha, setPushedCommitSha] = useState<string | null>(null);
  const [copiedKey, setCopiedKey] = useState(false);

  if (!isOpen) return null;

  const handleCopySshKey = () => {
    navigator.clipboard.writeText(SSH_PUBLIC_KEY);
    setCopiedKey(true);
    setTimeout(() => setCopiedKey(false), 2000);
  };

  const handleVerifyGitHub = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!token.trim()) {
      setErrorMessage('Please enter a GitHub Personal Access Token (PAT).');
      return;
    }

    setIsVerifying(true);
    setErrorMessage(null);
    setStatusMessage(null);
    try {
      const client = new GitHubClient(token);
      const user = await client.getCurrentUser();
      setVerifiedUser(user);
      if (user.login) setRepoOwner(user.login);

      const repo = await client.getRepo(user.login || repoOwner, repoName);
      setExistingRepo(repo);

      if (repo) {
        setStatusMessage(`Connected! Found repository "${repo.full_name}" on GitHub.`);
      } else {
        setStatusMessage(
          `Connected as @${user.login}! Repository "${repoOwner}/${repoName}" was not found yet. You can create it with 1 click below.`
        );
      }
    } catch (err: any) {
      setErrorMessage(err.message || 'Failed to authenticate with GitHub.');
    } finally {
      setIsVerifying(false);
    }
  };

  const handleCreateRepo = async () => {
    if (!token.trim()) return;
    setIsCreatingRepo(true);
    setErrorMessage(null);
    try {
      const client = new GitHubClient(token);
      const repo = await client.createRepo(
        repoName,
        isPrivate,
        'Hermes Duplex Android Client (v0.79-poc) - Low-latency duplex voice & chat'
      );
      setExistingRepo(repo);
      setStatusMessage(`Repository "${repo.full_name}" created successfully on GitHub!`);
    } catch (err: any) {
      setErrorMessage(err.message || 'Failed to create repository on GitHub.');
    } finally {
      setIsCreatingRepo(false);
    }
  };

  const handlePushFiles = async () => {
    if (!token.trim()) return;
    setIsPushing(true);
    setErrorMessage(null);
    try {
      const client = new GitHubClient(token);
      const targetOwner = verifiedUser?.login || repoOwner;

      const commitSha = await client.pushFiles(
        targetOwner,
        repoName,
        allProjectFiles,
        'feat: modernize Hermes Duplex Android client (Compose UI, zero-alloc ring buffer, Android 14)'
      );

      setPushedCommitSha(commitSha);
      setStatusMessage(
        `Successfully pushed ${allProjectFiles.length} files to https://github.com/${targetOwner}/${repoName}!`
      );
    } catch (err: any) {
      setErrorMessage(`Push failed: ${err.message}`);
    } finally {
      setIsPushing(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-xs select-none">
      <div className="w-full max-w-xl bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[92vh]">
        {/* Header */}
        <div className="px-5 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-950/70">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-slate-800 border border-slate-700 flex items-center justify-center text-white">
              <Github className="w-5 h-5" />
            </div>
            <div>
              <h3 className="font-semibold text-slate-100 text-sm flex items-center gap-2">
                GitHub & SSH Connection Center
              </h3>
              <p className="text-xs text-slate-400 font-mono">
                Target: github.com/{repoOwner}/{repoName}
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
            onClick={() => setActiveTab('token')}
            className={`flex items-center gap-2 px-3.5 py-2 rounded-t-lg font-medium transition cursor-pointer ${
              activeTab === 'token'
                ? 'bg-slate-900 text-cyan-300 border-t border-x border-slate-800'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Key className="w-3.5 h-3.5" />
            GitHub Token (Instant 1-Click Push)
          </button>

          <button
            onClick={() => setActiveTab('ssh')}
            className={`flex items-center gap-2 px-3.5 py-2 rounded-t-lg font-medium transition cursor-pointer ${
              activeTab === 'ssh'
                ? 'bg-slate-900 text-cyan-300 border-t border-x border-slate-800'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Terminal className="w-3.5 h-3.5" />
            SSH Key Connect
          </button>
        </div>

        {/* Body Content */}
        <div className="p-5 overflow-y-auto space-y-4 flex-1 text-xs">
          {/* Notifications */}
          {errorMessage && (
            <div className="p-3 rounded-xl bg-rose-500/10 border border-rose-500/20 text-rose-300 flex items-center gap-2">
              <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
              <span>{errorMessage}</span>
            </div>
          )}

          {statusMessage && (
            <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-300 flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
              <span>{statusMessage}</span>
            </div>
          )}

          {/* TAB 1: GITHUB TOKEN */}
          {activeTab === 'token' && (
            <div className="space-y-4">
              <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-slate-200 flex items-center gap-1.5">
                    <Key className="w-3.5 h-3.5 text-cyan-400" />
                    GitHub Personal Access Token (PAT)
                  </span>
                  <a
                    href="https://github.com/settings/tokens/new?scopes=repo&description=Hermes+Duplex+Studio"
                    target="_blank"
                    rel="noreferrer"
                    className="text-[11px] text-cyan-400 hover:text-cyan-300 inline-flex items-center gap-1 font-medium"
                  >
                    Generate Token <ExternalLink className="w-3 h-3" />
                  </a>
                </div>

                <div className="flex gap-2">
                  <input
                    type="password"
                    value={token}
                    onChange={(e) => setToken(e.target.value)}
                    placeholder="ghp_... or github_pat_..."
                    className="flex-1 bg-slate-900 border border-slate-800 rounded-xl px-3 py-2 text-slate-200 font-mono focus:outline-none focus:border-cyan-500 transition"
                  />
                  <button
                    onClick={handleVerifyGitHub}
                    disabled={isVerifying || !token.trim()}
                    className="px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-medium transition cursor-pointer disabled:opacity-40"
                  >
                    {isVerifying ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : 'Connect'}
                  </button>
                </div>
                <p className="text-[10px] text-slate-500">
                  Needs <code className="text-slate-300">repo</code> scope to create or push files to your repository.
                </p>
              </div>

              {/* Repo Status & 1-Click Push Card */}
              {verifiedUser && (
                <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <img
                        src={verifiedUser.avatar_url}
                        alt="Avatar"
                        className="w-6 h-6 rounded-full"
                      />
                      <span className="font-semibold text-slate-200">
                        {verifiedUser.name || verifiedUser.login}
                      </span>
                      <span className="text-[10px] text-slate-500 font-mono">
                        (@{verifiedUser.login})
                      </span>
                    </div>

                    <span className="text-[10px] px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-400 font-mono">
                      Authenticated
                    </span>
                  </div>

                  <div className="pt-2 border-t border-slate-800/80 flex items-center justify-between">
                    <div>
                      <p className="font-mono text-slate-300 text-xs">
                        {verifiedUser.login}/{repoName}
                      </p>
                      <p className="text-[10px] text-slate-500">
                        {existingRepo
                          ? `Existing repository (${existingRepo.private ? 'Private' : 'Public'})`
                          : 'Repository does not exist yet'}
                      </p>
                    </div>

                    {!existingRepo ? (
                      <button
                        onClick={handleCreateRepo}
                        disabled={isCreatingRepo}
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-cyan-600 hover:bg-cyan-500 text-white font-medium transition cursor-pointer disabled:opacity-50"
                      >
                        {isCreatingRepo ? (
                          <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                        ) : (
                          <UploadCloud className="w-3.5 h-3.5" />
                        )}
                        Create Repo on GitHub
                      </button>
                    ) : (
                      <button
                        onClick={handlePushFiles}
                        disabled={isPushing}
                        className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-semibold transition cursor-pointer shadow-md disabled:opacity-50"
                      >
                        {isPushing ? (
                          <>
                            <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                            Pushing {allProjectFiles.length} files...
                          </>
                        ) : (
                          <>
                            <UploadCloud className="w-4 h-4" />
                            Push All Files to GitHub
                          </>
                        )}
                      </button>
                    )}
                  </div>

                  {existingRepo && !pushedCommitSha && (
                    <button
                      onClick={handlePushFiles}
                      disabled={isPushing}
                      className="w-full py-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-semibold transition cursor-pointer shadow-md disabled:opacity-50 flex items-center justify-center gap-2 mt-2"
                    >
                      <UploadCloud className="w-4 h-4" />
                      {isPushing ? 'Pushing 17 Files to GitHub...' : `Push All 17 Files to ${verifiedUser.login}/${repoName}`}
                    </button>
                  )}

                  {pushedCommitSha && (
                    <div className="p-3 rounded-lg bg-emerald-950/40 border border-emerald-500/30 text-emerald-300 text-xs flex items-center justify-between">
                      <span className="font-mono text-[11px]">
                        Commit SHA: {pushedCommitSha.substring(0, 7)}
                      </span>
                      <a
                        href={`https://github.com/${verifiedUser.login}/${repoName}`}
                        target="_blank"
                        rel="noreferrer"
                        className="text-cyan-400 hover:text-cyan-300 font-medium inline-flex items-center gap-1"
                      >
                        View on GitHub <ExternalLink className="w-3 h-3" />
                      </a>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

          {/* TAB 2: SSH KEY CONNECT */}
          {activeTab === 'ssh' && (
            <div className="space-y-4">
              <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 space-y-2.5">
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-slate-200 flex items-center gap-1.5">
                    <Shield className="w-4 h-4 text-emerald-400" />
                    Generated SSH Public Key (ED25519)
                  </span>
                  <button
                    onClick={handleCopySshKey}
                    className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium transition cursor-pointer"
                  >
                    {copiedKey ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                    {copiedKey ? 'Copied' : 'Copy Key'}
                  </button>
                </div>

                <pre className="p-3 rounded-lg bg-slate-900 border border-slate-800 font-mono text-[10px] text-cyan-300 overflow-x-auto select-all whitespace-pre-wrap break-all leading-relaxed">
                  {SSH_PUBLIC_KEY}
                </pre>

                <div className="pt-2 flex items-center justify-between text-[11px] text-slate-400">
                  <span>Add this key to your GitHub account:</span>
                  <a
                    href="https://github.com/settings/ssh/new"
                    target="_blank"
                    rel="noreferrer"
                    className="text-cyan-400 hover:text-cyan-300 inline-flex items-center gap-1 font-medium"
                  >
                    Open GitHub SSH Settings <ExternalLink className="w-3 h-3" />
                  </a>
                </div>
              </div>

              {/* SSH Instructions */}
              <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800 text-xs text-slate-300 space-y-2">
                <span className="font-semibold text-slate-200">How to authenticate with SSH:</span>
                <ol className="list-decimal list-inside space-y-1.5 text-slate-400 text-[11px] leading-relaxed">
                  <li>Click <strong>Copy Key</strong> above.</li>
                  <li>
                    Go to <strong>GitHub Settings &gt; SSH and GPG keys &gt; New SSH key</strong>.
                  </li>
                  <li>Paste the key, title it <code className="text-slate-300">Hermes Duplex Studio</code>, and save.</li>
                  <li>
                    Your remote is already configured to:
                    <br />
                    <code className="text-cyan-300 font-mono text-[10px]">
                      git@github.com:Xz0n3X/hermes-duplex.git
                    </code>
                  </li>
                </ol>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
