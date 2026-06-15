/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync.model;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import java.io.Serializable;
import java.time.Instant;

@XStreamAlias("syncRecord")
public class SyncRecord implements Serializable {

  private static final long serialVersionUID = 1L;

  public enum Action {
    SAVE,
    REMOVE,
    DEPLOY,
    UNDEPLOY,
    SYNC,
    PROMOTE
  }

  public enum ArtifactType {
    CHANNEL,
    CODE_TEMPLATE_LIBRARY,
    GLOBAL_SCRIPT,
    CHANNEL_GROUP,
    CONFIG_MAP
  }

  private String artifactId;
  private String artifactName;
  private ArtifactType artifactType;
  private Action action;
  private String commitHash;
  private String message;
  private Instant timestamp;
  private boolean success;
  private String error;

  public SyncRecord() {
    this.timestamp = Instant.now();
    this.success = true;
  }

  public static SyncRecord success(
      ArtifactType type, Action action, String id, String name, String commitHash) {
    SyncRecord record = new SyncRecord();
    record.artifactType = type;
    record.action = action;
    record.artifactId = id;
    record.artifactName = name;
    record.commitHash = commitHash;
    record.message = action + " " + type + ": " + name;
    return record;
  }

  public static SyncRecord failure(
      ArtifactType type, Action action, String id, String name, String error) {
    SyncRecord record = new SyncRecord();
    record.artifactType = type;
    record.action = action;
    record.artifactId = id;
    record.artifactName = name;
    record.success = false;
    record.error = error;
    record.message = "Failed to " + action + " " + type + ": " + name + " - " + error;
    return record;
  }

  public String getArtifactId() {
    return artifactId;
  }

  public void setArtifactId(String artifactId) {
    this.artifactId = artifactId;
  }

  public String getArtifactName() {
    return artifactName;
  }

  public void setArtifactName(String artifactName) {
    this.artifactName = artifactName;
  }

  public ArtifactType getArtifactType() {
    return artifactType;
  }

  public void setArtifactType(ArtifactType artifactType) {
    this.artifactType = artifactType;
  }

  public Action getAction() {
    return action;
  }

  public void setAction(Action action) {
    this.action = action;
  }

  public String getCommitHash() {
    return commitHash;
  }

  public void setCommitHash(String commitHash) {
    this.commitHash = commitHash;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }
}
