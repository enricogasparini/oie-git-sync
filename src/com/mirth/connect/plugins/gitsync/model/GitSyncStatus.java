/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync.model;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@XStreamAlias("gitSyncStatus")
public class GitSyncStatus implements Serializable {

  private static final long serialVersionUID = 1L;

  private boolean enabled;
  private boolean repoInitialised;
  private String branch;
  private String lastCommitHash;
  private Instant lastSyncTime;
  private String lastError;
  private Instant lastErrorTime;
  private int totalSyncs;
  private int totalErrors;
  private String environmentName;
  private String nodeRole;
  private int pendingChangeCount;
  private List<SyncRecord> recentRecords = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isRepoInitialised() {
    return repoInitialised;
  }

  public void setRepoInitialised(boolean repoInitialised) {
    this.repoInitialised = repoInitialised;
  }

  public String getBranch() {
    return branch;
  }

  public void setBranch(String branch) {
    this.branch = branch;
  }

  public String getLastCommitHash() {
    return lastCommitHash;
  }

  public void setLastCommitHash(String lastCommitHash) {
    this.lastCommitHash = lastCommitHash;
  }

  public Instant getLastSyncTime() {
    return lastSyncTime;
  }

  public void setLastSyncTime(Instant lastSyncTime) {
    this.lastSyncTime = lastSyncTime;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public Instant getLastErrorTime() {
    return lastErrorTime;
  }

  public void setLastErrorTime(Instant lastErrorTime) {
    this.lastErrorTime = lastErrorTime;
  }

  public int getTotalSyncs() {
    return totalSyncs;
  }

  public void setTotalSyncs(int totalSyncs) {
    this.totalSyncs = totalSyncs;
  }

  public int getTotalErrors() {
    return totalErrors;
  }

  public void setTotalErrors(int totalErrors) {
    this.totalErrors = totalErrors;
  }

  public String getEnvironmentName() {
    return environmentName;
  }

  public void setEnvironmentName(String environmentName) {
    this.environmentName = environmentName;
  }

  public String getNodeRole() {
    return nodeRole;
  }

  public void setNodeRole(String nodeRole) {
    this.nodeRole = nodeRole;
  }

  public int getPendingChangeCount() {
    return pendingChangeCount;
  }

  public void setPendingChangeCount(int pendingChangeCount) {
    this.pendingChangeCount = pendingChangeCount;
  }

  public List<SyncRecord> getRecentRecords() {
    return recentRecords;
  }

  public void setRecentRecords(List<SyncRecord> recentRecords) {
    this.recentRecords = recentRecords;
  }

  public void recordSuccess(String commitHash) {
    this.lastCommitHash = commitHash;
    this.lastSyncTime = Instant.now();
    this.totalSyncs++;
  }

  public void recordFailure(String error) {
    this.lastError = error;
    this.lastErrorTime = Instant.now();
    this.totalErrors++;
  }
}
