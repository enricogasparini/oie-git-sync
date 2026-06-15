/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.plugins.gitsync.model.GitSyncStatus;
import com.mirth.connect.plugins.gitsync.model.PendingChangeList;
import com.mirth.connect.plugins.gitsync.model.PromotionRequest;
import com.mirth.connect.plugins.gitsync.model.PromotionResult;
import com.mirth.connect.plugins.gitsync.model.SyncRecord;
import com.mirth.connect.server.api.MirthServlet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GitSyncServlet extends MirthServlet implements GitSyncServletInterface {

  private static final Logger logger = LogManager.getLogger(GitSyncServlet.class);

  public GitSyncServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
    super(request, sc, PLUGIN_POINT);
  }

  @Override
  public GitSyncStatus getStatus() {
    try {
      return GitSyncPlugin.getInstance().getStatus();
    } catch (Exception e) {
      throw new MirthApiException(e);
    }
  }

  @Override
  public GitSyncStatus triggerFullSync() {
    try {
      return GitSyncPlugin.getInstance().triggerFullSync();
    } catch (Exception e) {
      throw new MirthApiException(e);
    }
  }

  @Override
  public List<SyncRecord> getSyncLog(int limit) {
    try {
      return GitSyncPlugin.getInstance().getSyncLog(limit);
    } catch (Exception e) {
      throw new MirthApiException(e);
    }
  }

  @Override
  public String testConnection() {
    try {
      return GitSyncPlugin.getInstance().testConnection();
    } catch (Exception e) {
      throw new MirthApiException(e);
    }
  }

  @Override
  public GitSyncStatus resetLocalRepo() {
    try {
      GitSyncPlugin.getInstance().resetLocalRepo();
      return GitSyncPlugin.getInstance().getStatus();
    } catch (Exception e) {
      logger.error("Reset local repo failed", e);
      throw new MirthApiException(e);
    }
  }

  @Override
  public PromotionResult promote(PromotionRequest request) {
    validateApiKey();
    try {
      return GitSyncPlugin.getInstance().promote(request);
    } catch (Exception e) {
      logger.error("Promotion failed", e);
      throw new MirthApiException(e);
    }
  }

  @Override
  public PromotionResult previewPromotion(PromotionRequest request) {
    validateApiKey();
    try {
      request.setDryRun(true);
      return GitSyncPlugin.getInstance().promote(request);
    } catch (Exception e) {
      logger.error("Promotion preview failed", e);
      throw new MirthApiException(e);
    }
  }

  /**
   * If an API key is configured, validates the X-GitSync-API-Key header. Returns silently when no
   * key is configured (backward compatible). Throws 403 on mismatch. The comparison is
   * constant-time so response timing cannot leak how much of a guessed key matched.
   */
  private void validateApiKey() {
    String configuredKey = GitSyncPlugin.getInstance().getApiKey();
    if (configuredKey == null || configuredKey.isEmpty()) {
      return;
    }
    String provided = request.getHeader("X-GitSync-API-Key");
    boolean valid =
        provided != null
            && MessageDigest.isEqual(
                configuredKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    if (!valid) {
      throw new MirthApiException(
          Response.status(Response.Status.FORBIDDEN)
              .entity("Invalid or missing X-GitSync-API-Key header")
              .build());
    }
  }

  @Override
  public PendingChangeList getPending(String username) {
    try {
      String targetUser =
          (username != null && !username.isBlank()) ? username : getCurrentUsername();
      return GitSyncPlugin.getInstance().getPending(targetUser);
    } catch (Exception e) {
      throw new MirthApiException(e);
    }
  }

  @Override
  public GitSyncStatus commitPending(String username, String message) {
    try {
      String targetUser =
          (username != null && !username.isBlank()) ? username : getCurrentUsername();
      GitSyncPlugin.getInstance().commitPending(targetUser, message);
      return GitSyncPlugin.getInstance().getStatus();
    } catch (Exception e) {
      logger.error("Commit pending failed", e);
      throw new MirthApiException(e);
    }
  }

  @Override
  public GitSyncStatus discardPending(String username) {
    try {
      String targetUser =
          (username != null && !username.isBlank()) ? username : getCurrentUsername();
      GitSyncPlugin.getInstance().discardPending(targetUser);
      return GitSyncPlugin.getInstance().getStatus();
    } catch (Exception e) {
      throw new MirthApiException(e);
    }
  }

  private String getCurrentUsername() {
    try {
      int userId = getCurrentUserId();
      com.mirth.connect.model.User user =
          com.mirth.connect.server.controllers.ControllerFactory.getFactory()
              .createUserController()
              .getUser(userId, null);
      if (user != null && user.getUsername() != null) {
        return user.getUsername();
      }
    } catch (Exception e) {
      logger.warn("Failed to resolve current username", e);
    }
    return "unknown";
  }
}
