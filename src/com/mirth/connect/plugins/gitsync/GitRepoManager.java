/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

/**
 * Manages the local Git repository lifecycle and operations.
 *
 * <p>All Git operations are serialised via a fair ReentrantLock to prevent concurrent modification
 * issues when multiple users save channels simultaneously.
 */
public class GitRepoManager {

  private static final Logger logger = LogManager.getLogger(GitRepoManager.class);

  /** Short commit hash length used everywhere we display or log a hash. */
  public static final int SHORT_HASH_LEN = 8;

  private final ReentrantLock gitLock = new ReentrantLock(true);
  private final Path repoPath;

  private Git git;
  private String remoteName = "origin";
  private String branch = "main";
  private String authorName = "OIE Git Sync";
  private String authorEmail = "gitsync@oie.local";
  private boolean pushEnabled = true;
  private int pushRetryCount = 3;
  private long pushRetryDelayMs = 2000;
  private CredentialsProvider credentialsProvider;

  public GitRepoManager(Path repoPath) {
    this.repoPath = repoPath;
  }

  /**
   * Initialises or opens the local Git repository. If a remoteUrl is provided and the repo does not
   * exist, it will be cloned. If the repo exists, it will be opened. If neither, a new repo will be
   * initialised.
   */
  public void init(String remoteUrl) throws GitAPIException, IOException {
    gitLock.lock();
    try {
      File repoDir = repoPath.toFile();

      if (isGitRepo(repoDir)) {
        logger.info("Opening existing Git repository at {}", repoPath);
        git = Git.open(repoDir);

        // Ensure remote is configured (may have been added after initial init)
        if (remoteUrl != null && !remoteUrl.isBlank()) {
          ensureRemote(remoteUrl);
        }
      } else if (remoteUrl != null && !remoteUrl.isBlank()) {
        logger.info("Cloning from {} to {}", sanitiseUrl(remoteUrl), repoPath);
        Files.createDirectories(repoPath);
        git =
            Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(repoDir)
                .setBranch(branch)
                .setCredentialsProvider(credentialsProvider)
                .call();
      } else {
        logger.info("Initialising new Git repository at {}", repoPath);
        Files.createDirectories(repoPath);
        git = Git.init().setDirectory(repoDir).setInitialBranch(branch).call();
      }

      logger.info("Git repository ready at {}", repoPath);
    } catch (GitAPIException | java.net.URISyntaxException e) {
      throw new IOException("Failed to initialise Git repository at " + repoPath, e);
    } finally {
      gitLock.unlock();
    }
  }

  /**
   * Runs the given action while holding the repository lock. Used by promotion to keep the working
   * tree stable across the whole detect-and-read phase: without this, a concurrent commitToBranch
   * could check out a feature branch between the fetch and the file reads, and promotion would
   * import unreviewed feature-branch content. The lock is reentrant, so the action may call other
   * GitRepoManager operations freely.
   */
  public <T> T withRepoLock(java.util.concurrent.Callable<T> action) throws Exception {
    gitLock.lock();
    try {
      return action.call();
    } finally {
      gitLock.unlock();
    }
  }

  /**
   * Commits staged changes to a target feature branch and pushes.
   *
   * <p>Steps: 1. Fetch from remote 2. Checkout base branch, reset to origin/<base> (clean starting
   * state) 3. Resolve target branch (checkout existing or create new from current HEAD) 4. Apply
   * file operations: copy in MODIFY files, delete DELETE files 5. Stage additions/removals 6.
   * Commit with the given message 7. Push to the target feature branch 8. Checkout base branch
   * again (leave local repo in known state)
   *
   * @param targetBranch the feature branch to commit to (e.g. "gitsync/admin/2026-04-10")
   * @param fileOperations callback that applies file changes to the main working tree. Called after
   *     the target branch is checked out, before staging. Receives the repo root path.
   * @param pathsToAdd relative paths to stage (matches files the callback wrote/updated)
   * @param pathsToRemove relative paths to remove from the working tree and index
   * @param message commit message
   * @return the commit hash, or null if there was nothing to commit
   */
  public String commitToBranch(
      String targetBranch,
      FileOperationCallback fileOperations,
      List<String> pathsToAdd,
      List<String> pathsToRemove,
      String message)
      throws GitAPIException, IOException {
    return commitToBranch(
        targetBranch, fileOperations, pathsToAdd, pathsToRemove, message, null, null);
  }

  public String commitToBranch(
      String targetBranch,
      FileOperationCallback fileOperations,
      List<String> pathsToAdd,
      List<String> pathsToRemove,
      String message,
      String commitAuthorName,
      String commitAuthorEmail)
      throws GitAPIException, IOException {
    gitLock.lock();
    try {
      if (hasRemote()) {
        logger.debug("Fetching from '{}' (with prune)...", remoteName);
        git.fetch()
            .setRemote(remoteName)
            .setCredentialsProvider(credentialsProvider)
            .setRemoveDeletedRefs(true)
            .call();
      }

      // Step 2: checkout base branch and reset to origin/<base>
      checkoutBaseBranch();

      // Step 3: resolve target branch
      checkoutOrCreateBranch(targetBranch);

      // Step 4: apply file operations from the pending directory to the main working tree
      if (fileOperations != null) {
        fileOperations.apply(repoPath);
      }

      // Step 5: stage changes
      if (pathsToRemove != null && !pathsToRemove.isEmpty()) {
        RmCommand rm = git.rm();
        for (String path : pathsToRemove) {
          rm.addFilepattern(path);
        }
        try {
          rm.call();
        } catch (GitAPIException e) {
          logger.warn("Failed to rm some paths: {}", e.getMessage());
        }
      }
      if (pathsToAdd != null && !pathsToAdd.isEmpty()) {
        AddCommand add = git.add();
        for (String path : pathsToAdd) {
          add.addFilepattern(path);
        }
        add.call();

        // JGit's AddCommand stages new and modified files but not deletions. When the callback
        // removed files under an added prefix (e.g. a full sync re-serialising a directory that
        // previously contained a since-deleted channel), stage those deletions explicitly so the
        // commit reflects the removal.
        Status addStatus = git.status().call();
        List<String> missingUnderPrefixes = new ArrayList<>();
        for (String missing : addStatus.getMissing()) {
          for (String prefix : pathsToAdd) {
            if (missing.equals(prefix) || missing.startsWith(prefix + "/")) {
              missingUnderPrefixes.add(missing);
              break;
            }
          }
        }
        if (!missingUnderPrefixes.isEmpty()) {
          RmCommand rmMissing = git.rm();
          for (String path : missingUnderPrefixes) {
            rmMissing.addFilepattern(path);
          }
          rmMissing.call();
        }
      }

      // Check if there's anything to commit
      Status status = git.status().call();
      if (status.getChanged().isEmpty()
          && status.getAdded().isEmpty()
          && status.getRemoved().isEmpty()
          && status.getMissing().isEmpty()) {
        logger.debug("Nothing to commit on branch {}", targetBranch);
        // Return to base branch before returning
        checkoutBaseBranch();
        return null;
      }

      // Step 6: commit
      String effectiveAuthorName =
          (commitAuthorName != null && !commitAuthorName.isBlank()) ? commitAuthorName : authorName;
      String effectiveAuthorEmail =
          (commitAuthorEmail != null && !commitAuthorEmail.isBlank())
              ? commitAuthorEmail
              : authorEmail;
      RevCommit commit =
          git.commit()
              .setMessage(message)
              .setAuthor(effectiveAuthorName, effectiveAuthorEmail)
              .call();
      String hash = commit.getName();
      logger.info(
          "Committed to {} as {} <{}>: {} ({})",
          LogSanitiser.clean(targetBranch),
          LogSanitiser.clean(effectiveAuthorName),
          LogSanitiser.clean(effectiveAuthorEmail),
          LogSanitiser.clean(message),
          shortHash(hash));

      // Step 7: push
      if (pushEnabled && hasRemote()) {
        pushBranch(targetBranch);
      }

      // Step 8: return to base branch
      checkoutBaseBranch();

      return hash;
    } finally {
      gitLock.unlock();
    }
  }

  /**
   * Callback interface for applying file operations during a branch commit. The callback runs while
   * the repository is checked out on the target branch, with the lock held, just before staging.
   */
  @FunctionalInterface
  public interface FileOperationCallback {
    void apply(Path repoPath) throws IOException;
  }

  private void checkoutBaseBranch() throws GitAPIException, IOException {
    // A previous commitToBranch may have failed mid-flight (e.g. push exhaustion or an IOException
    // from the file-operations callback), leaving the repo on the feature branch with a dirty
    // working tree. A plain checkout would then fail with a CheckoutConflictException and wedge
    // every subsequent commit, so discard any local modifications first.
    if (git.getRepository().resolve("HEAD") != null) {
      git.reset().setMode(ResetCommand.ResetType.HARD).call();
    }
    String currentBranch = git.getRepository().getBranch();
    if (!branch.equals(currentBranch)) {
      try {
        git.checkout().setName(branch).call();
      } catch (RefNotFoundException e) {
        // Local base branch doesn't exist yet - create it tracking the remote
        if (hasRemote()) {
          Ref remoteRef = git.getRepository().findRef("refs/remotes/" + remoteName + "/" + branch);
          if (remoteRef != null) {
            git.checkout()
                .setCreateBranch(true)
                .setName(branch)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .setStartPoint(remoteName + "/" + branch)
                .call();
          } else {
            throw new IOException(
                "Base branch '" + branch + "' does not exist locally or on remote", e);
          }
        } else {
          throw e;
        }
      }
    }
    // Reset hard to remote tip if we have a remote
    if (hasRemote()) {
      Ref remoteRef = git.getRepository().findRef("refs/remotes/" + remoteName + "/" + branch);
      if (remoteRef != null) {
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteRef.getName()).call();
      }
    }
  }

  private void checkoutOrCreateBranch(String branchName) throws GitAPIException, IOException {
    Repository repo = git.getRepository();

    // Check if local branch exists
    Ref localRef = repo.findRef("refs/heads/" + branchName);
    Ref remoteRef =
        hasRemote() ? repo.findRef("refs/remotes/" + remoteName + "/" + branchName) : null;

    if (localRef != null && remoteRef == null && hasRemote()) {
      // Local branch exists but the remote was deleted (PR merged + branch deleted).
      // The local branch is stale - delete it and recreate from the current base.
      // We're already on the base branch (just reset to origin/<base> in checkoutBaseBranch).
      logger.info(
          "Local branch {} is stale (remote deleted - typically post-merge). "
              + "Deleting and recreating from {}/{}.",
          branchName,
          remoteName,
          branch);
      try {
        git.branchDelete().setBranchNames(branchName).setForce(true).call();
      } catch (GitAPIException e) {
        logger.warn("Failed to delete stale local branch {}: {}", branchName, e.getMessage());
      }
      git.checkout().setCreateBranch(true).setName(branchName).call();
    } else if (localRef != null) {
      // Local branch exists - check it out
      git.checkout().setName(branchName).call();
      // If there's a remote tracking branch, fast-forward to it
      if (remoteRef != null) {
        try {
          git.merge()
              .include(remoteRef)
              .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
              .call();
        } catch (GitAPIException e) {
          logger.warn(
              "Fast-forward of {} to {}/{} failed: {}",
              branchName,
              remoteName,
              branchName,
              e.getMessage());
        }
      }
    } else if (remoteRef != null) {
      // Remote has the branch, check it out with tracking
      git.checkout()
          .setCreateBranch(true)
          .setName(branchName)
          .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
          .setStartPoint(remoteName + "/" + branchName)
          .call();
    } else {
      // Create new branch from current HEAD (which should be the latest base)
      git.checkout().setCreateBranch(true).setName(branchName).call();
    }
    logger.debug("Checked out branch {}", branchName);
  }

  private void pushBranch(String branchName) throws GitAPIException {
    GitAPIException lastException = null;
    for (int attempt = 1; attempt <= pushRetryCount; attempt++) {
      try {
        git.push()
            .setRemote(remoteName)
            .add(branchName)
            .setCredentialsProvider(credentialsProvider)
            .call();
        logger.debug("Push of {} succeeded on attempt {}", branchName, attempt);
        return;
      } catch (GitAPIException e) {
        lastException = e;
        logger.warn(
            "Push attempt {}/{} for {} failed: {}",
            attempt,
            pushRetryCount,
            branchName,
            e.getMessage());
        if (attempt < pushRetryCount) {
          try {
            Thread.sleep(computeRetryDelayMs(attempt));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw e;
          }
        }
      }
    }
    logger.error("Push of {} failed after {} attempts", branchName, pushRetryCount, lastException);
    throw lastException;
  }

  private static final long MAX_RETRY_DELAY_MS = 30_000;

  /**
   * Computes the retry delay for the given attempt using exponential backoff: {@code
   * pushRetryDelayMs * 2^(attempt-1)}, capped at 30 seconds.
   *
   * <p>Package-private for testability.
   */
  long computeRetryDelayMs(int attempt) {
    long delay = pushRetryDelayMs * (1L << (attempt - 1));
    return Math.min(delay, MAX_RETRY_DELAY_MS);
  }

  /**
   * Tests connectivity to the remote by performing a ls-remote. Returns a success message or throws
   * on failure.
   */
  public String testConnection() throws Exception {
    gitLock.lock();
    try {
      if (!hasRemote()) {
        throw new IllegalStateException("No remote configured");
      }
      var refs =
          git.lsRemote()
              .setRemote(remoteName)
              .setCredentialsProvider(credentialsProvider)
              .setHeads(true)
              .call();
      return "Connected successfully. Found " + refs.size() + " remote branch(es).";
    } finally {
      gitLock.unlock();
    }
  }

  /**
   * Returns a list of changed file paths between two commits (or since the beginning if fromCommit
   * is null). Only returns paths under the given prefix (e.g. "channels/").
   */
  public List<String> getChangedPaths(String fromCommitHash, String toCommitHash, String pathPrefix)
      throws IOException, GitAPIException {
    gitLock.lock();
    try {
      Repository repo = git.getRepository();
      List<String> paths = new ArrayList<>();

      try (ObjectReader reader = repo.newObjectReader();
          RevWalk revWalk = new RevWalk(repo)) {
        // Resolve the "to" commit (HEAD if not specified)
        ObjectId toId =
            toCommitHash != null && !toCommitHash.isBlank()
                ? repo.resolve(toCommitHash)
                : repo.resolve("HEAD");
        if (toId == null) {
          return paths;
        }
        RevCommit toCommit = revWalk.parseCommit(toId);
        RevTree toTree = toCommit.getTree();
        CanonicalTreeParser newTreeParser = new CanonicalTreeParser();
        newTreeParser.reset(reader, toTree);

        CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
        if (fromCommitHash != null && !fromCommitHash.isBlank()) {
          ObjectId fromId = repo.resolve(fromCommitHash);
          if (fromId != null) {
            RevCommit fromCommit = revWalk.parseCommit(fromId);
            oldTreeParser.reset(reader, fromCommit.getTree());
          }
        }
        // If fromCommit is null, oldTreeParser is empty - meaning everything in toTree is "new"

        List<DiffEntry> diffs =
            git.diff().setOldTree(oldTreeParser).setNewTree(newTreeParser).call();

        for (DiffEntry diff : diffs) {
          String path =
              diff.getChangeType() == DiffEntry.ChangeType.DELETE
                  ? diff.getOldPath()
                  : diff.getNewPath();
          if (pathPrefix == null || path.startsWith(pathPrefix)) {
            paths.add(path);
          }
        }
      }

      return paths;
    } finally {
      gitLock.unlock();
    }
  }

  /**
   * Fetches from remote and resets the local branch to match the remote. Used by promotion to get
   * the latest approved state from Git.
   */
  public String fetchAndReset() throws GitAPIException, IOException {
    return fetchAndReset(null);
  }

  /**
   * Fetches from remote and resets the working tree to the given commit, or to the remote tip of
   * the base branch when {@code targetCommitHash} is null/blank. Promotion uses the pinned variant
   * so the files it reads from the working tree belong to the commit being promoted, not to
   * whatever HEAD has since moved to.
   *
   * @return the full hash of the commit the working tree now reflects
   */
  public String fetchAndReset(String targetCommitHash) throws GitAPIException, IOException {
    gitLock.lock();
    try {
      if (hasRemote()) {
        logger.info("Fetching from remote '{}' (with prune)...", remoteName);
        git.fetch()
            .setRemote(remoteName)
            .setCredentialsProvider(credentialsProvider)
            .setRemoveDeletedRefs(true)
            .call();
      }

      if (targetCommitHash != null && !targetCommitHash.isBlank()) {
        // resolve() parses any full-length hex string without checking existence, so verify the
        // object is actually present — a typo'd pin must fail loudly, not promote HEAD.
        ObjectId target = git.getRepository().resolve(targetCommitHash);
        if (target == null || !git.getRepository().getObjectDatabase().has(target)) {
          throw new IOException(
              "Commit '" + targetCommitHash + "' could not be resolved in the local repository");
        }
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(target.getName()).call();
        logger.info("Reset working tree to pinned commit {}", shortHash(target.getName()));
        return target.getName();
      }

      if (hasRemote()) {
        ObjectId remoteHead =
            git.getRepository().resolve("refs/remotes/" + remoteName + "/" + branch);
        if (remoteHead != null) {
          git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteHead.getName()).call();
          logger.info(
              "Reset local branch to {}/{} ({})",
              remoteName,
              branch,
              shortHash(remoteHead.getName()));
          return remoteHead.getName();
        } else {
          logger.warn(
              "No remote tracking branch found for {}/{}, using local HEAD", remoteName, branch);
        }
      }
      return getLatestCommitHash();
    } finally {
      gitLock.unlock();
    }
  }

  public Path getRepoPath() {
    return repoPath;
  }

  /** Fetches from the remote. */
  public void fetch() throws GitAPIException {
    gitLock.lock();
    try {
      if (hasRemote()) {
        git.fetch()
            .setRemote(remoteName)
            .setCredentialsProvider(credentialsProvider)
            .setRemoveDeletedRefs(true)
            .call();
        logger.debug("Fetched from {} (with prune)", remoteName);
      }
    } finally {
      gitLock.unlock();
    }
  }

  public void close() {
    gitLock.lock();
    try {
      if (git != null) {
        git.close();
        git = null;
      }
    } finally {
      gitLock.unlock();
    }
  }

  /**
   * Nukes the local clone and re-clones from the remote. Used to recover from stale refs, corrupt
   * working tree, or other local-only issues.
   */
  public void resetLocalRepo(String remoteUrl) throws GitAPIException, IOException {
    gitLock.lock();
    try {
      if (git != null) {
        git.close();
        git = null;
      }
      // Delete the local repo directory entirely
      if (Files.exists(repoPath)) {
        logger.info("Resetting local repo - deleting {}", repoPath);
        FileUtils.deleteRecursively(repoPath);
      }
      // Re-initialise (will clone fresh if remoteUrl is set)
      init(remoteUrl);
      logger.info("Local repo reset complete");
    } finally {
      gitLock.unlock();
    }
  }

  // Status getters take gitLock so a concurrent resetLocalRepo() cannot null or close the
  // underlying Git instance between the null-check and the use.

  public boolean isInitialised() {
    gitLock.lock();
    try {
      return git != null;
    } finally {
      gitLock.unlock();
    }
  }

  public String getCurrentBranch() throws IOException {
    gitLock.lock();
    try {
      if (git == null) return null;
      return git.getRepository().getBranch();
    } finally {
      gitLock.unlock();
    }
  }

  public String getLatestCommitHash() {
    gitLock.lock();
    try {
      if (git == null) {
        return null;
      }
      var iter = git.log().setMaxCount(1).call().iterator();
      return iter.hasNext() ? iter.next().getName() : null;
    } catch (GitAPIException e) {
      logger.debug("Could not read latest commit hash", e);
      return null;
    } finally {
      gitLock.unlock();
    }
  }

  // --- Configuration setters ---
  //
  // All config mutation goes through gitLock so it cannot race with an
  // in-flight commit/push/fetch. These are individually synchronised rather
  // than via a single configure(Config) call so the existing caller shape in
  // GitSyncPlugin.applyProperties() stays unchanged — a future refactor may
  // collapse them into one atomic setter.

  public void setRemoteName(String remoteName) {
    gitLock.lock();
    try {
      this.remoteName = remoteName;
    } finally {
      gitLock.unlock();
    }
  }

  public void setBranch(String branch) {
    gitLock.lock();
    try {
      this.branch = branch;
    } finally {
      gitLock.unlock();
    }
  }

  public void setAuthorName(String authorName) {
    gitLock.lock();
    try {
      this.authorName = authorName;
    } finally {
      gitLock.unlock();
    }
  }

  public void setAuthorEmail(String authorEmail) {
    gitLock.lock();
    try {
      this.authorEmail = authorEmail;
    } finally {
      gitLock.unlock();
    }
  }

  public void setPushEnabled(boolean pushEnabled) {
    gitLock.lock();
    try {
      this.pushEnabled = pushEnabled;
    } finally {
      gitLock.unlock();
    }
  }

  public void setPushRetryCount(int pushRetryCount) {
    gitLock.lock();
    try {
      this.pushRetryCount = pushRetryCount;
    } finally {
      gitLock.unlock();
    }
  }

  public void setPushRetryDelayMs(long pushRetryDelayMs) {
    gitLock.lock();
    try {
      this.pushRetryDelayMs = pushRetryDelayMs;
    } finally {
      gitLock.unlock();
    }
  }

  public void setCredentials(String username, String password) {
    gitLock.lock();
    try {
      if (username != null && !username.isBlank()) {
        this.credentialsProvider = new UsernamePasswordCredentialsProvider(username, password);
      } else {
        this.credentialsProvider = null;
      }
    } finally {
      gitLock.unlock();
    }
  }

  // --- Internal helpers ---

  /**
   * Returns the first {@link #SHORT_HASH_LEN} characters of a commit hash, or {@code "?"} if the
   * hash is null, for display and log messages.
   */
  public static String shortHash(String hash) {
    if (hash == null) {
      return "?";
    }
    return hash.length() > SHORT_HASH_LEN ? hash.substring(0, SHORT_HASH_LEN) : hash;
  }

  /**
   * Strips any userinfo (user:token@) from an HTTP(S) or SSH URL so credentials embedded in the URL
   * aren't written to logs.
   */
  static String sanitiseUrl(String url) {
    if (url == null) {
      return null;
    }
    try {
      URIish uri = new URIish(url);
      if (uri.getUser() == null && uri.getPass() == null) {
        return url;
      }
      return uri.setUser(null).setPass(null).toString();
    } catch (java.net.URISyntaxException e) {
      // Not a URI shape we understand — best-effort: strip anything before @
      int at = url.indexOf('@');
      int scheme = url.indexOf("://");
      if (at > 0 && scheme >= 0 && at > scheme) {
        return url.substring(0, scheme + 3) + url.substring(at + 1);
      }
      return url;
    }
  }

  private boolean isGitRepo(File dir) {
    File gitDir = new File(dir, ".git");
    return gitDir.exists() && gitDir.isDirectory();
  }

  private boolean hasRemote() {
    if (git == null) {
      return false;
    }
    return git.getRepository().getRemoteNames().contains(remoteName);
  }

  private void ensureRemote(String remoteUrl)
      throws GitAPIException, IOException, java.net.URISyntaxException {
    if (hasRemote()) {
      // Update existing remote URL if it's changed
      StoredConfig config = git.getRepository().getConfig();
      String existingUrl = config.getString("remote", remoteName, "url");
      if (!remoteUrl.equals(existingUrl)) {
        config.setString("remote", remoteName, "url", remoteUrl);
        config.save();
        logger.info("Updated remote '{}' URL", remoteName);
      }
    } else {
      git.remoteAdd().setName(remoteName).setUri(new URIish(remoteUrl)).call();
      logger.info("Added remote '{}'", remoteName);
    }
  }
}
