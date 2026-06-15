/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.plugins.gitsync.model.GitSyncStatus;
import com.mirth.connect.plugins.gitsync.model.PendingChangeList;
import com.mirth.connect.plugins.gitsync.model.PromotionRequest;
import com.mirth.connect.plugins.gitsync.model.PromotionResult;
import com.mirth.connect.plugins.gitsync.model.SyncRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

@Path("/extensions/gitsync")
@Tag(name = "Extension Services")
@Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public interface GitSyncServletInterface extends BaseServletInterface {

  String PLUGIN_POINT = "Git Sync";

  String PERMISSION_VIEW = "View Status";
  String PERMISSION_MANAGE = "Manage Settings";
  String PERMISSION_PROMOTE = "Promote";

  @GET
  @Path("/status")
  @Operation(summary = "Retrieves the current Git sync status.")
  @MirthOperation(
      name = "getGitSyncStatus",
      display = "Get sync status",
      permission = PERMISSION_VIEW)
  GitSyncStatus getStatus() throws ClientException;

  @POST
  @Path("/_sync")
  @Operation(summary = "Triggers a full sync of all artefacts to Git.")
  @MirthOperation(
      name = "triggerFullSync",
      display = "Trigger full sync",
      permission = PERMISSION_MANAGE)
  GitSyncStatus triggerFullSync() throws ClientException;

  @GET
  @Path("/log")
  @Operation(summary = "Retrieves the recent sync log entries.")
  @MirthOperation(name = "getSyncLog", display = "Get sync log", permission = PERMISSION_VIEW)
  List<SyncRecord> getSyncLog(@QueryParam("limit") @DefaultValue("50") int limit)
      throws ClientException;

  @POST
  @Path("/_testConnection")
  @Operation(summary = "Tests connectivity to the configured remote Git repository.")
  @MirthOperation(
      name = "testConnection",
      display = "Test connection",
      permission = PERMISSION_MANAGE)
  String testConnection() throws ClientException;

  @POST
  @Path("/_resetLocalRepo")
  @Operation(summary = "Deletes the local Git clone and re-clones from the remote.")
  @MirthOperation(
      name = "resetLocalRepo",
      display = "Reset local repo",
      permission = PERMISSION_MANAGE)
  GitSyncStatus resetLocalRepo() throws ClientException;

  @POST
  @Path("/promote")
  @Operation(summary = "Promotes channel configurations from Git to this OIE instance.")
  @MirthOperation(name = "promote", display = "Promote from Git", permission = PERMISSION_PROMOTE)
  PromotionResult promote(PromotionRequest request) throws ClientException;

  @POST
  @Path("/promote/preview")
  @Operation(summary = "Previews what would change if a promotion were applied.")
  @MirthOperation(
      name = "previewPromotion",
      display = "Preview promotion",
      permission = PERMISSION_PROMOTE)
  PromotionResult previewPromotion(PromotionRequest request) throws ClientException;

  @GET
  @Path("/pending")
  @Operation(summary = "Returns pending changes for the specified user.")
  @MirthOperation(
      name = "getPending",
      display = "Get pending changes",
      permission = PERMISSION_VIEW)
  PendingChangeList getPending(@QueryParam("username") String username) throws ClientException;

  @POST
  @Path("/pending/commit")
  @Operation(summary = "Commits pending changes for the specified user to their feature branch.")
  @MirthOperation(
      name = "commitPending",
      display = "Commit pending changes",
      permission = PERMISSION_MANAGE)
  GitSyncStatus commitPending(
      @QueryParam("username") String username, @QueryParam("message") String message)
      throws ClientException;

  @POST
  @Path("/pending/discard")
  @Operation(summary = "Discards pending changes for the specified user.")
  @MirthOperation(
      name = "discardPending",
      display = "Discard pending changes",
      permission = PERMISSION_MANAGE)
  GitSyncStatus discardPending(@QueryParam("username") String username) throws ClientException;
}
