/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import com.mirth.connect.client.ui.AbstractSettingsPanel;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.SettingsPanelPlugin;
import com.mirth.connect.plugins.gitsync.model.GitSyncStatus;
import com.mirth.connect.plugins.gitsync.model.PendingChange;
import com.mirth.connect.plugins.gitsync.model.PendingChangeList;
import com.mirth.connect.plugins.gitsync.model.PromotionRequest;
import com.mirth.connect.plugins.gitsync.model.PromotionResult;
import com.mirth.connect.plugins.gitsync.model.SyncRecord;

public class GitSyncClient extends SettingsPanelPlugin {

  private AbstractSettingsPanel settingsPanel;

  public GitSyncClient(String name) {
    super(name);
    // The server registers these aliases in GitSyncPlugin.init(). The admin
    // console runs in a separate JVM, so its ObjectXMLSerializer doesn't know
    // about them — Jersey's JAX-RS proxy would fail to deserialise the short
    // element names and the settings panel would show "Unknown" status.
    registerXStreamAliases();
    settingsPanel = new GitSyncSettingsPanel("Git Sync", this);
  }

  private static void registerXStreamAliases() {
    try {
      ObjectXMLSerializer.getInstance()
          .processAnnotations(
              new Class<?>[] {
                GitSyncStatus.class,
                PendingChange.class,
                PendingChangeList.class,
                PromotionRequest.class,
                PromotionResult.class,
                SyncRecord.class,
              });
    } catch (Exception ignored) {
      // If registration fails (e.g. the serializer is not yet initialised),
      // the panel will still work but fall back to fully-qualified element
      // names when deserialising REST responses.
    }
  }

  @Override
  public AbstractSettingsPanel getSettingsPanel() {
    return settingsPanel;
  }

  @Override
  public void start() {}

  @Override
  public void stop() {}

  @Override
  public void reset() {}

  @Override
  public String getPluginPointName() {
    return "Git Sync";
  }
}
