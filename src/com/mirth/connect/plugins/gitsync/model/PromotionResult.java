/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync.model;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@XStreamAlias("promotionResult")
public class PromotionResult implements Serializable {

  private static final long serialVersionUID = 1L;

  private boolean success;
  private String commitHash;
  private boolean dryRun;
  private int channelsImported;
  private int channelsDeployed;
  private List<SyncRecord> records = new ArrayList<>();
  private List<String> errors = new ArrayList<>();
  private List<String> warnings = new ArrayList<>();

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getCommitHash() {
    return commitHash;
  }

  public void setCommitHash(String commitHash) {
    this.commitHash = commitHash;
  }

  public boolean isDryRun() {
    return dryRun;
  }

  public void setDryRun(boolean dryRun) {
    this.dryRun = dryRun;
  }

  public int getChannelsImported() {
    return channelsImported;
  }

  public void setChannelsImported(int channelsImported) {
    this.channelsImported = channelsImported;
  }

  public int getChannelsDeployed() {
    return channelsDeployed;
  }

  public void setChannelsDeployed(int channelsDeployed) {
    this.channelsDeployed = channelsDeployed;
  }

  public List<SyncRecord> getRecords() {
    return records;
  }

  public void setRecords(List<SyncRecord> records) {
    this.records = records;
  }

  public List<String> getErrors() {
    return errors;
  }

  public void setErrors(List<String> errors) {
    this.errors = errors;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public void setWarnings(List<String> warnings) {
    this.warnings = warnings;
  }

  public void addRecord(SyncRecord record) {
    records.add(record);
  }

  public void addError(String error) {
    errors.add(error);
  }

  public void addWarning(String warning) {
    warnings.add(warning);
  }
}
