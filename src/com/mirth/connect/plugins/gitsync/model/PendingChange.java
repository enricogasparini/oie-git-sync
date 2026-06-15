/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync.model;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import java.io.Serializable;
import java.time.Instant;

@XStreamAlias("pendingChange")
public class PendingChange implements Serializable {

  private static final long serialVersionUID = 1L;

  public enum Type {
    CHANNEL,
    CODE_TEMPLATE_LIBRARY
  }

  public enum Action {
    MODIFY,
    DELETE
  }

  private Type type;
  private String id;
  private String name;
  private Action action;
  private int revision;
  private Instant recordedAt;

  public PendingChange() {
    this.recordedAt = Instant.now();
  }

  public PendingChange(Type type, String id, String name, Action action, int revision) {
    this();
    this.type = type;
    this.id = id;
    this.name = name;
    this.action = action;
    this.revision = revision;
  }

  public Type getType() {
    return type;
  }

  public void setType(Type type) {
    this.type = type;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Action getAction() {
    return action;
  }

  public void setAction(Action action) {
    this.action = action;
  }

  public int getRevision() {
    return revision;
  }

  public void setRevision(int revision) {
    this.revision = revision;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }

  public void setRecordedAt(Instant recordedAt) {
    this.recordedAt = recordedAt;
  }

  /** Unique key for this change in a manifest. Format: TYPE:id */
  public String getKey() {
    return type + ":" + id;
  }
}
