/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync.model;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import java.io.Serializable;
import java.util.Set;

@XStreamAlias("promotionRequest")
public class PromotionRequest implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Git commit hash to promote from. Null or empty means HEAD of configured branch. */
  private String commitHash;

  /** Whether to deploy channels after import. */
  private boolean deploy;

  /** Whether to overwrite existing channels that have been modified locally. */
  private boolean overwrite = true;

  /** Specific channel IDs to promote. Null means all changed channels. */
  private Set<String> channelIds;

  /** Preview only - don't apply changes. */
  private boolean dryRun;

  /** Recovery mode: ignore lastPromotedCommit and import everything from the target. */
  private boolean fresh;

  public String getCommitHash() {
    return commitHash;
  }

  public void setCommitHash(String commitHash) {
    this.commitHash = commitHash;
  }

  public boolean isDeploy() {
    return deploy;
  }

  public void setDeploy(boolean deploy) {
    this.deploy = deploy;
  }

  public boolean isOverwrite() {
    return overwrite;
  }

  public void setOverwrite(boolean overwrite) {
    this.overwrite = overwrite;
  }

  public Set<String> getChannelIds() {
    return channelIds;
  }

  public void setChannelIds(Set<String> channelIds) {
    this.channelIds = channelIds;
  }

  public boolean isDryRun() {
    return dryRun;
  }

  public void setDryRun(boolean dryRun) {
    this.dryRun = dryRun;
  }

  public boolean isFresh() {
    return fresh;
  }

  public void setFresh(boolean fresh) {
    this.fresh = fresh;
  }
}
