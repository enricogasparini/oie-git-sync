/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Logs Git sync operations as OIE server events for audit purposes. */
public class SyncEventLogger {

  private static final Logger logger = LogManager.getLogger(SyncEventLogger.class);

  private final EventController eventController;

  public SyncEventLogger() {
    this.eventController = ControllerFactory.getFactory().createEventController();
  }

  public void logSync(String channelName, String action, String commitHash) {
    String message =
        String.format(
            "%s channel '%s' (commit %s)",
            action, channelName, GitRepoManager.shortHash(commitHash));
    dispatchEvent(message, ServerEvent.Level.INFORMATION, ServerEvent.Outcome.SUCCESS);
  }

  public void logSyncFailure(String channelName, String action, String error) {
    String message = String.format("Failed to %s channel '%s': %s", action, channelName, error);
    dispatchEvent(message, ServerEvent.Level.ERROR, ServerEvent.Outcome.FAILURE);
  }

  public void logFullSync(int count, String commitHash) {
    String message =
        String.format(
            "Full sync completed - %d artefacts (commit %s)",
            count, GitRepoManager.shortHash(commitHash));
    dispatchEvent(message, ServerEvent.Level.INFORMATION, ServerEvent.Outcome.SUCCESS);
  }

  private void dispatchEvent(String message, ServerEvent.Level level, ServerEvent.Outcome outcome) {
    try {
      String serverId = ConfigurationController.getInstance().getServerId();
      ServerEvent event =
          new ServerEvent(
              serverId,
              GitSyncServletInterface.PLUGIN_POINT + ": " + message,
              level,
              outcome,
              null);
      eventController.dispatchEvent(event);
    } catch (Exception e) {
      logger.warn("Failed to dispatch server event: {}", message, e);
    }
  }
}
