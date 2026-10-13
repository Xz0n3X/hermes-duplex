// GitHub API Client for repo creation and direct file pushing via Git Data API

export interface GitHubUser {
  login: string;
  name: string;
  avatar_url: string;
  html_url: string;
}

export interface GitHubRepo {
  name: string;
  full_name: string;
  private: boolean;
  html_url: string;
  default_branch: string;
}

export class GitHubClient {
  private token: string;

  constructor(token: string) {
    this.token = token.trim();
  }

  private async request(endpoint: string, options: RequestInit = {}) {
    const url = endpoint.startsWith('https://') ? endpoint : `https://api.github.com${endpoint}`;
    const res = await fetch(url, {
      ...options,
      headers: {
        Authorization: `Bearer ${this.token}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        ...(options.headers || {}),
      },
    });

    if (!res.ok) {
      const errText = await res.text();
      let message = `GitHub API Error (${res.status})`;
      try {
        const parsed = JSON.parse(errText);
        if (parsed.message) message = parsed.message;
      } catch {}
      throw new Error(message);
    }

    if (res.status === 204) return null;
    return await res.json();
  }

  async getCurrentUser(): Promise<GitHubUser> {
    return await this.request('/user');
  }

  async getRepo(owner: string, repo: string): Promise<GitHubRepo | null> {
    try {
      return await this.request(`/repos/${owner}/${repo}`);
    } catch (err: any) {
      if (err.message.includes('404') || err.message.includes('Not Found')) {
        return null;
      }
      throw err;
    }
  }

  async createRepo(name: string, isPrivate: boolean = true, description: string = ''): Promise<GitHubRepo> {
    return await this.request('/user/repos', {
      method: 'POST',
      body: JSON.stringify({
        name,
        private: isPrivate,
        description: description || 'Hermes Duplex Android Client',
        auto_init: false,
      }),
    });
  }

  /**
   * Pushes files to GitHub using the Git Data API (blob -> tree -> commit -> update ref)
   */
  async pushFiles(
    owner: string,
    repo: string,
    files: { path: string; content: string }[],
    commitMessage: string = 'Update Hermes Duplex Android client'
  ): Promise<string> {
    // 1. Get default branch ref or check if repo is empty
    let parentCommitSha: string | null = null;
    let baseTreeSha: string | null = null;

    try {
      const refData = await this.request(`/repos/${owner}/${repo}/git/ref/heads/main`);
      parentCommitSha = refData.object.sha;
      const commitData = await this.request(`/repos/${owner}/${repo}/git/commits/${parentCommitSha}`);
      baseTreeSha = commitData.tree.sha;
    } catch {
      // Branch doesn't exist or repo empty, try master
      try {
        const refMaster = await this.request(`/repos/${owner}/${repo}/git/ref/heads/master`);
        parentCommitSha = refMaster.object.sha;
        const commitData = await this.request(`/repos/${owner}/${repo}/git/commits/${parentCommitSha}`);
        baseTreeSha = commitData.tree.sha;
      } catch {
        // Brand new empty repository
      }
    }

    // 2. Create blobs for each file
    const treeItems: { path: string; mode: string; type: string; sha: string }[] = [];

    for (const file of files) {
      const blobData = await this.request(`/repos/${owner}/${repo}/git/blobs`, {
        method: 'POST',
        body: JSON.stringify({
          content: file.content,
          encoding: 'utf-8',
        }),
      });

      treeItems.push({
        path: file.path,
        mode: '100644', // normal file
        type: 'blob',
        sha: blobData.sha,
      });
    }

    // 3. Create tree
    const treePayload: any = { tree: treeItems };
    if (baseTreeSha) {
      treePayload.base_tree = baseTreeSha;
    }

    const treeData = await this.request(`/repos/${owner}/${repo}/git/trees`, {
      method: 'POST',
      body: JSON.stringify(treePayload),
    });

    // 4. Create commit
    const commitPayload: any = {
      message: commitMessage,
      tree: treeData.sha,
      parents: parentCommitSha ? [parentCommitSha] : [],
    };

    const newCommit = await this.request(`/repos/${owner}/${repo}/git/commits`, {
      method: 'POST',
      body: JSON.stringify(commitPayload),
    });

    // 5. Update or create branch ref
    if (parentCommitSha) {
      await this.request(`/repos/${owner}/${repo}/git/refs/heads/main`, {
        method: 'PATCH',
        body: JSON.stringify({
          sha: newCommit.sha,
          force: true,
        }),
      });
    } else {
      await this.request(`/repos/${owner}/${repo}/git/refs`, {
        method: 'POST',
        body: JSON.stringify({
          ref: 'refs/heads/main',
          sha: newCommit.sha,
        }),
      });
    }

    return newCommit.sha;
  }
}
