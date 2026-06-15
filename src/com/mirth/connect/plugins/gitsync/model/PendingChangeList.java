/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync.model;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@XStreamAlias("pendingChangeList")
public class PendingChangeList implements Serializable {

  private static final long serialVersionUID = 1L;

  private String username;
  private int version = 1;
  private String updated;
  private List<PendingChange> changes = new ArrayList<>();

  public PendingChangeList() {}

  public PendingChangeList(String username) {
    this.username = username;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public String getUpdated() {
    return updated;
  }

  public void setUpdated(String updated) {
    this.updated = updated;
  }

  public List<PendingChange> getChanges() {
    return changes;
  }

  public void setChanges(List<PendingChange> changes) {
    this.changes = changes;
  }

  public int size() {
    return changes.size();
  }

  public boolean isEmpty() {
    return changes.isEmpty();
  }
}
