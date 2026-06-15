/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.ui.AbstractSettingsPanel;
import com.mirth.connect.client.ui.Frame;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.UIConstants;
import com.mirth.connect.client.ui.components.MirthTextField;
import com.mirth.connect.plugins.SettingsPanelPlugin;
import com.mirth.connect.plugins.gitsync.model.GitSyncStatus;
import com.mirth.connect.plugins.gitsync.model.PendingChange;
import com.mirth.connect.plugins.gitsync.model.PendingChangeList;
import com.mirth.connect.plugins.gitsync.model.PromotionRequest;
import com.mirth.connect.plugins.gitsync.model.PromotionResult;
import com.mirth.connect.plugins.gitsync.model.SyncRecord;
import java.awt.Color;
import java.awt.Font;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import net.miginfocom.swing.MigLayout;

public class GitSyncSettingsPanel extends AbstractSettingsPanel {

  private static final Color STATUS_OK = new Color(0, 128, 0);
  private static final Color STATUS_ERROR = new Color(200, 0, 0);
  private static final Color STATUS_DISABLED = new Color(128, 128, 128);
  private static final DateTimeFormatter TIME_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  private SettingsPanelPlugin plugin;
  private Frame parent;
  private AtomicBoolean refreshing = new AtomicBoolean(false);

  // Status bar
  private JLabel statusIndicator;
  private JLabel lastSyncLabel;
  private JLabel lastCommitLabel;
  private JLabel syncCountLabel;
  private JLabel errorCountLabel;
  private JLabel pendingCountLabel;

  // General
  private JCheckBox enabledCheckBox;
  private MirthTextField environmentNameField;
  private JComboBox<String> nodeRoleCombo;

  // Repository
  private MirthTextField repoPathField;
  private MirthTextField remoteUrlField;
  private MirthTextField branchField;
  private JCheckBox pushEnabledCheckBox;

  // Credentials
  private JComboBox<String> credentialTypeCombo;
  private MirthTextField credentialUsernameField;
  private JPasswordField credentialPasswordField;
  private JLabel credentialUsernameLabel;
  private JLabel credentialPasswordLabel;

  /** True if the server reported a stored credential on the last refresh. */
  private boolean hasStoredPassword;

  // Commit
  private MirthTextField authorNameField;
  private MirthTextField authorEmailField;
  private MirthTextField commitBranchPatternField;

  // Scope
  private JCheckBox syncChannelsCheckBox;
  private JCheckBox syncCodeTemplatesCheckBox;
  private JCheckBox syncGlobalScriptsCheckBox;
  private JCheckBox syncChannelGroupsCheckBox;

  // Sync log
  private JTable syncLogTable;
  private DefaultTableModel syncLogModel;

  // Task indices for show/hide
  private int commitTaskIdx;
  private int snapshotTaskIdx;
  private int importTaskIdx;
  private int restoreTaskIdx;

  public GitSyncSettingsPanel(String tabName, SettingsPanelPlugin plugin) {
    super(tabName);
    this.plugin = plugin;
    this.parent = PlatformUI.MIRTH_FRAME;

    commitTaskIdx =
        addTask(
            "doCommitToGit",
            "Commit to Git",
            "Commit your pending changes to Git.",
            "",
            new ImageIcon(Frame.class.getResource("images/disk.png")));
    snapshotTaskIdx =
        addTask(
            "doSnapshotToGit",
            "Snapshot to Git",
            "Bootstrap or capture: push a full snapshot of all OIE state to Git "
                + "(channels, code templates, scripts, groups, config map keys).",
            "",
            new ImageIcon(Frame.class.getResource("images/arrow_refresh.png")));
    importTaskIdx =
        addTask(
            "doImportFromGit",
            "Import from Git",
            "Import changes from Git into this OIE instance. Shows a preview first.",
            "",
            new ImageIcon(Frame.class.getResource("images/arrow_down.png")));
    restoreTaskIdx =
        addTask(
            "doRestoreFromMain",
            "Restore from Main",
            "Recovery: import everything from the main branch, ignoring last promoted state.",
            "",
            new ImageIcon(Frame.class.getResource("images/arrow_undo.png")));
    addTask(
        "doTestConnection",
        "Test Connection",
        "Test connectivity to the remote Git repository.",
        "",
        new ImageIcon(Frame.class.getResource("images/accept.png")));
    addTask(
        "doResetLocalRepo",
        "Reset Local Repo",
        "Recovery: delete the local Git clone and re-clone from the remote.",
        "",
        new ImageIcon(Frame.class.getResource("images/cross.png")));

    initComponents();
    initLayout();
  }

  @Override
  public void doRefresh() {
    if (refreshing.getAndSet(true)) {
      return;
    }

    final String workingId = parent.startWorking("Loading Git Sync settings...");

    SwingWorker<Properties, Void> worker =
        new SwingWorker<Properties, Void>() {
          private GitSyncStatus syncStatus;

          @Override
          public Properties doInBackground() {
            try {
              Properties props = plugin.getPropertiesFromServer();
              try {
                syncStatus =
                    parent.mirthClient.getServlet(GitSyncServletInterface.class).getStatus();
              } catch (Exception e) {
                syncStatus = null;
              }
              return props;
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
              return null;
            }
          }

          @Override
          public void done() {
            try {
              Properties properties = get();
              if (properties != null) {
                setProperties(properties);
              }
              if (syncStatus != null) {
                updateStatusBar(syncStatus);
                updateSyncLog(syncStatus.getRecentRecords());
              }
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
            } finally {
              parent.stopWorking(workingId);
              refreshing.set(false);
            }
          }
        };
    worker.execute();
  }

  @Override
  public boolean doSave() {
    if (!validateFields()) {
      return false;
    }

    final Properties propertiesToSave = getProperties();
    final String workingId = parent.startWorking("Saving Git Sync settings...");

    SwingWorker<Void, Void> worker =
        new SwingWorker<Void, Void>() {
          @Override
          public Void doInBackground() {
            try {
              plugin.setPropertiesToServer(propertiesToSave);
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
            }
            return null;
          }

          @Override
          public void done() {
            setSaveEnabled(false);
            parent.stopWorking(workingId);
          }
        };
    worker.execute();

    return true;
  }

  // --- Task callbacks ---

  public void doTestConnection() {
    final String workingId = parent.startWorking("Testing connection...");

    SwingWorker<String, Void> worker =
        new SwingWorker<String, Void>() {
          @Override
          public String doInBackground() {
            try {
              return parent.mirthClient.getServlet(GitSyncServletInterface.class).testConnection();
            } catch (ClientException e) {
              return "Connection failed: " + e.getMessage();
            }
          }

          @Override
          public void done() {
            try {
              String result = get();
              parent.alertInformation(parent, result);
            } catch (Exception e) {
              parent.alertError(parent, "Connection test failed: " + e.getMessage());
            } finally {
              parent.stopWorking(workingId);
            }
          }
        };
    worker.execute();
  }

  public void doSnapshotToGit() {
    int response =
        JOptionPane.showConfirmDialog(
            parent,
            "Snapshot to Git will push a complete snapshot of all channels, code template"
                + " libraries, global scripts, channel groups, and the configuration map template"
                + " (keys only) to a dedicated 'gitsync/fullsync/{date}' branch.\n\n"
                + "This is intended for bootstrapping an empty Git repo or capturing the current"
                + " OIE state.\n\n"
                + "Continue?",
            "Snapshot to Git",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE);
    if (response != JOptionPane.YES_OPTION) {
      return;
    }

    final String workingId = parent.startWorking("Pushing snapshot to Git...");
    SwingWorker<Void, Void> worker =
        new SwingWorker<Void, Void>() {
          @Override
          public Void doInBackground() {
            try {
              parent.mirthClient.getServlet(GitSyncServletInterface.class).triggerFullSync();
            } catch (ClientException e) {
              parent.alertThrowable(parent, e);
            }
            return null;
          }

          @Override
          public void done() {
            parent.stopWorking(workingId);
            doRefresh();
          }
        };
    worker.execute();
  }

  public void doImportFromGit() {
    // Show preview first, then offer Apply
    runImport(false);
  }

  public void doResetLocalRepo() {
    int response =
        JOptionPane.showConfirmDialog(
            parent,
            "Reset Local Repo will:\n\n"
                + "  1. Delete the local Git clone directory\n"
                + "  2. Re-clone from the configured remote\n"
                + "  3. Discard ALL pending changes (across all users)\n\n"
                + "Use this for recovery from stale refs, corrupt working tree, or other "
                + "local-only Git problems. Anything not yet pushed to Git will be lost.\n\n"
                + "Continue?",
            "Reset Local Repo",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
    if (response != JOptionPane.YES_OPTION) {
      return;
    }

    final String workingId = parent.startWorking("Resetting local repo...");
    SwingWorker<Void, Void> worker =
        new SwingWorker<Void, Void>() {
          @Override
          public Void doInBackground() {
            try {
              parent.mirthClient.getServlet(GitSyncServletInterface.class).resetLocalRepo();
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
            }
            return null;
          }

          @Override
          public void done() {
            parent.stopWorking(workingId);
            parent.alertInformation(
                parent, "Local repo has been reset and re-cloned from the remote.");
            doRefresh();
          }
        };
    worker.execute();
  }

  public void doRestoreFromMain() {
    int response =
        JOptionPane.showConfirmDialog(
            parent,
            "Restore from Main will import ALL channels from the main branch into this OIE"
                + " instance, ignoring the last promoted state. Existing channels with the same IDs"
                + " will be overwritten.\n\n"
                + "Use this for recovery scenarios where the OIE database has been wiped or"
                + " rebuilt.\n\n"
                + "Continue?",
            "Restore from Main",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
    if (response != JOptionPane.YES_OPTION) {
      return;
    }
    runImport(true);
  }

  /**
   * Two-step import flow: preview first, then offer Apply.
   *
   * @param fresh if true, ignore lastPromotedCommit (Restore from Main)
   */
  private void runImport(boolean fresh) {
    final String workingId = parent.startWorking("Previewing changes...");
    final boolean isRestore = fresh;

    SwingWorker<PromotionResult, Void> worker =
        new SwingWorker<PromotionResult, Void>() {
          @Override
          public PromotionResult doInBackground() {
            try {
              PromotionRequest req = new PromotionRequest();
              req.setOverwrite(true);
              req.setDryRun(true);
              req.setFresh(fresh);
              return parent
                  .mirthClient
                  .getServlet(GitSyncServletInterface.class)
                  .previewPromotion(req);
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
              return null;
            }
          }

          @Override
          public void done() {
            parent.stopWorking(workingId);
            try {
              PromotionResult result = get();
              if (result == null) return;

              // No changes? Just inform the user.
              if (result.getRecords().isEmpty()) {
                String msg =
                    "No changes detected since last " + (isRestore ? "restore" : "promotion") + ".";
                if (!result.getWarnings().isEmpty()) {
                  msg += "\n\n" + String.join("\n", result.getWarnings());
                }
                parent.alertInformation(parent, msg);
                return;
              }

              // Show preview dialog with Apply / Cancel
              showImportPreviewDialog(result, isRestore);
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
            }
          }
        };
    worker.execute();
  }

  private void showImportPreviewDialog(PromotionResult preview, boolean fresh) {
    String title = fresh ? "Restore from Main - Preview" : "Import from Git - Preview";
    JDialog dialog = new JDialog(parent, title, true);
    dialog.setLayout(new MigLayout("insets 12, fill, gap 6", "[grow,fill]", "[][grow,fill][][]"));
    dialog.setSize(700, 500);
    dialog.setLocationRelativeTo(parent);

    dialog.add(new JLabel("The following changes would be applied:"), "wrap");

    DefaultTableModel model =
        new DefaultTableModel(new String[] {"Type", "Action", "Name"}, 0) {
          @Override
          public boolean isCellEditable(int row, int column) {
            return false;
          }
        };
    for (SyncRecord rec : preview.getRecords()) {
      model.addRow(new Object[] {rec.getArtifactType(), rec.getAction(), rec.getArtifactName()});
    }
    JTable table = new JTable(model);
    table.setRowHeight(20);
    dialog.add(new JScrollPane(table), "grow, wrap");

    JCheckBox deployCheckbox = new JCheckBox("Deploy channels after import");
    deployCheckbox.setBackground(UIConstants.BACKGROUND_COLOR);
    dialog.add(deployCheckbox, "wrap");

    JPanel buttonPanel = new JPanel(new MigLayout("insets 0, gap 6", "[grow][][]"));
    JButton cancelButton = new JButton("Cancel");
    JButton applyButton = new JButton("Apply");
    applyButton.setBackground(new Color(0, 120, 215));
    applyButton.setForeground(Color.WHITE);
    buttonPanel.add(Box.createHorizontalGlue(), "grow");
    buttonPanel.add(cancelButton);
    buttonPanel.add(applyButton);
    dialog.add(buttonPanel, "growx");

    cancelButton.addActionListener(e -> dialog.dispose());
    applyButton.addActionListener(
        e -> {
          dialog.dispose();
          executeImport(fresh, deployCheckbox.isSelected());
        });

    dialog.setVisible(true);
  }

  private void executeImport(boolean fresh, boolean deploy) {
    final String workingId = parent.startWorking("Importing from Git...");
    SwingWorker<PromotionResult, Void> worker =
        new SwingWorker<PromotionResult, Void>() {
          @Override
          public PromotionResult doInBackground() {
            try {
              PromotionRequest req = new PromotionRequest();
              req.setOverwrite(true);
              req.setDryRun(false);
              req.setFresh(fresh);
              req.setDeploy(deploy);
              return parent.mirthClient.getServlet(GitSyncServletInterface.class).promote(req);
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
              return null;
            }
          }

          @Override
          public void done() {
            parent.stopWorking(workingId);
            try {
              PromotionResult result = get();
              if (result != null) {
                int channels = 0;
                int libraries = 0;
                int groups = 0;
                int scripts = 0;
                int configMap = 0;
                for (SyncRecord rec : result.getRecords()) {
                  if (!rec.isSuccess()) {
                    continue;
                  }
                  switch (rec.getArtifactType()) {
                    case CHANNEL:
                      channels++;
                      break;
                    case CODE_TEMPLATE_LIBRARY:
                      libraries++;
                      break;
                    case CHANNEL_GROUP:
                      groups++;
                      break;
                    case GLOBAL_SCRIPT:
                      scripts++;
                      break;
                    case CONFIG_MAP:
                      configMap++;
                      break;
                    default:
                      break;
                  }
                }

                StringBuilder msg = new StringBuilder("Imported from Git:\n\n");
                msg.append("  Channels:               ").append(channels).append("\n");
                msg.append("  Code template libs:     ").append(libraries).append("\n");
                msg.append("  Channel groups:         ").append(groups).append("\n");
                msg.append("  Global scripts:         ").append(scripts).append("\n");
                msg.append("  Config map updates:     ").append(configMap).append("\n");
                if (result.getChannelsDeployed() > 0) {
                  msg.append("\nDeployed: ")
                      .append(result.getChannelsDeployed())
                      .append(" channel(s)");
                }
                if (!result.getWarnings().isEmpty()) {
                  msg.append("\n\nWarnings:\n").append(String.join("\n", result.getWarnings()));
                }
                if (!result.getErrors().isEmpty()) {
                  msg.append("\n\nErrors:\n").append(String.join("\n", result.getErrors()));
                }
                parent.alertInformation(parent, msg.toString());
              }
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
            }
            doRefresh();
          }
        };
    worker.execute();
  }

  private void applyRoleVisibility(NodeRole role) {
    setVisibleTasks(commitTaskIdx, commitTaskIdx, role.isContributor());
    setVisibleTasks(snapshotTaskIdx, snapshotTaskIdx, role.isContributor());
    setVisibleTasks(importTaskIdx, importTaskIdx, role.isReceiver());
    setVisibleTasks(restoreTaskIdx, restoreTaskIdx, role.isReceiver());
  }

  public void doCommitToGit() {
    final String workingId = parent.startWorking("Loading pending changes...");

    SwingWorker<PendingChangeList, Void> worker =
        new SwingWorker<PendingChangeList, Void>() {
          @Override
          public PendingChangeList doInBackground() {
            try {
              return parent.mirthClient.getServlet(GitSyncServletInterface.class).getPending(null);
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
              return null;
            }
          }

          @Override
          public void done() {
            parent.stopWorking(workingId);
            try {
              PendingChangeList pending = get();
              if (pending == null || pending.isEmpty()) {
                parent.alertInformation(parent, "You have no pending changes to commit.");
                return;
              }
              showCommitDialog(pending);
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
            }
          }
        };
    worker.execute();
  }

  private void showCommitDialog(PendingChangeList pending) {
    JDialog dialog = new JDialog(parent, "Commit to Git", true);
    dialog.setLayout(new MigLayout("insets 12, fill, gap 6", "[grow,fill]", "[][grow,fill][][]"));
    dialog.setSize(600, 450);
    dialog.setLocationRelativeTo(parent);

    dialog.add(new JLabel("The following changes will be committed:"), "wrap");

    DefaultTableModel model =
        new DefaultTableModel(new String[] {"Type", "Action", "Name"}, 0) {
          @Override
          public boolean isCellEditable(int row, int column) {
            return false;
          }
        };
    for (PendingChange c : pending.getChanges()) {
      model.addRow(new Object[] {c.getType(), c.getAction(), c.getName()});
    }
    JTable table = new JTable(model);
    table.setRowHeight(20);
    dialog.add(new JScrollPane(table), "grow, wrap");

    dialog.add(new JLabel("Commit message (optional):"), "wrap");
    JTextField messageField = new JTextField();
    dialog.add(messageField, "growx, wrap");

    JPanel buttonPanel = new JPanel(new MigLayout("insets 0, gap 6", "[][grow][][]"));
    JButton discardButton = new JButton("Discard All");
    discardButton.setForeground(new Color(160, 0, 0));
    JButton cancelButton = new JButton("Cancel");
    JButton commitButton = new JButton("Commit");
    commitButton.setBackground(new Color(0, 120, 215));
    commitButton.setForeground(Color.WHITE);
    buttonPanel.add(discardButton);
    buttonPanel.add(Box.createHorizontalGlue(), "grow");
    buttonPanel.add(cancelButton);
    buttonPanel.add(commitButton);
    dialog.add(buttonPanel, "growx");

    cancelButton.addActionListener(e -> dialog.dispose());
    commitButton.addActionListener(
        e -> {
          dialog.dispose();
          executeCommit(messageField.getText());
        });
    discardButton.addActionListener(
        e -> {
          int confirm =
              JOptionPane.showConfirmDialog(
                  dialog,
                  "Discard ALL pending changes? This cannot be undone.\n\n"
                      + "(The channels in OIE are not affected - this only clears the Git sync"
                      + " queue.)",
                  "Discard Pending Changes",
                  JOptionPane.YES_NO_OPTION,
                  JOptionPane.WARNING_MESSAGE);
          if (confirm == JOptionPane.YES_OPTION) {
            dialog.dispose();
            executeDiscard();
          }
        });

    dialog.setVisible(true);
  }

  private void executeDiscard() {
    final String workingId = parent.startWorking("Discarding pending changes...");
    SwingWorker<Void, Void> worker =
        new SwingWorker<Void, Void>() {
          @Override
          public Void doInBackground() {
            try {
              parent.mirthClient.getServlet(GitSyncServletInterface.class).discardPending(null);
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
            }
            return null;
          }

          @Override
          public void done() {
            parent.stopWorking(workingId);
            doRefresh();
          }
        };
    worker.execute();
  }

  private void executeCommit(String message) {
    final String workingId = parent.startWorking("Committing to Git...");
    SwingWorker<GitSyncStatus, Void> worker =
        new SwingWorker<GitSyncStatus, Void>() {
          @Override
          public GitSyncStatus doInBackground() {
            try {
              return parent
                  .mirthClient
                  .getServlet(GitSyncServletInterface.class)
                  .commitPending(null, message);
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
              return null;
            }
          }

          @Override
          public void done() {
            parent.stopWorking(workingId);
            try {
              GitSyncStatus status = get();
              if (status != null) {
                parent.alertInformation(
                    parent,
                    "Committed to branch (last commit: "
                        + shortHash(status.getLastCommitHash())
                        + ")");
              }
            } catch (Exception e) {
              parent.alertThrowable(parent, e);
            }
            doRefresh();
          }
        };
    worker.execute();
  }

  // --- Status bar ---

  private void updateStatusBar(GitSyncStatus status) {
    if (status.isEnabled() && status.isRepoInitialised()) {
      statusIndicator.setText("Active");
      statusIndicator.setForeground(STATUS_OK);
    } else if (status.isEnabled()) {
      statusIndicator.setText("Enabled (repo not initialised)");
      statusIndicator.setForeground(STATUS_ERROR);
    } else {
      statusIndicator.setText("Disabled");
      statusIndicator.setForeground(STATUS_DISABLED);
    }

    lastSyncLabel.setText(
        status.getLastSyncTime() != null ? TIME_FMT.format(status.getLastSyncTime()) : "Never");
    lastCommitLabel.setText(
        status.getLastCommitHash() != null ? shortHash(status.getLastCommitHash()) : "-");
    syncCountLabel.setText(String.valueOf(status.getTotalSyncs()));

    errorCountLabel.setText(String.valueOf(status.getTotalErrors()));
    errorCountLabel.setForeground(status.getTotalErrors() > 0 ? STATUS_ERROR : STATUS_OK);

    pendingCountLabel.setText(String.valueOf(status.getPendingChangeCount()));
    pendingCountLabel.setForeground(status.getPendingChangeCount() > 0 ? STATUS_ERROR : STATUS_OK);
  }

  // --- Sync log table ---

  private void updateSyncLog(List<SyncRecord> records) {
    syncLogModel.setRowCount(0);
    if (records != null) {
      for (SyncRecord record : records) {
        syncLogModel.addRow(
            new Object[] {
              record.getTimestamp() != null ? TIME_FMT.format(record.getTimestamp()) : "",
              record.getAction(),
              record.getArtifactName(),
              record.getCommitHash() != null ? shortHash(record.getCommitHash()) : "-",
              record.isSuccess() ? "OK" : "FAILED",
              record.isSuccess() ? "" : record.getError()
            });
      }
    }
  }

  // --- Properties ---

  private void setProperties(Properties properties) {
    refreshing.set(true);
    try {
      enabledCheckBox.setSelected(
          Boolean.parseBoolean(properties.getProperty(GitSyncProperties.ENABLED, "false")));
      environmentNameField.setText(
          properties.getProperty(
              GitSyncProperties.ENVIRONMENT_NAME, GitSyncProperties.DEFAULT_ENVIRONMENT));
      NodeRole role = NodeRole.parse(properties.getProperty(GitSyncProperties.NODE_ROLE));
      nodeRoleCombo.setSelectedItem(role.name());
      applyRoleVisibility(role);
      repoPathField.setText(properties.getProperty(GitSyncProperties.REPO_PATH, ""));
      remoteUrlField.setText(properties.getProperty(GitSyncProperties.REMOTE_URL, ""));
      branchField.setText(
          properties.getProperty(GitSyncProperties.BRANCH, GitSyncProperties.DEFAULT_BRANCH));
      pushEnabledCheckBox.setSelected(
          Boolean.parseBoolean(properties.getProperty(GitSyncProperties.PUSH_ENABLED, "true")));

      CredentialType credType =
          CredentialType.parse(properties.getProperty(GitSyncProperties.CREDENTIAL_TYPE));
      credentialTypeCombo.setSelectedItem(credType.name());
      credentialUsernameField.setText(
          properties.getProperty(GitSyncProperties.CREDENTIAL_USERNAME, ""));
      // Never round-trip the stored credential to the client. The server preserves
      // the existing value on save when this field is left blank.
      credentialPasswordField.setText("");
      hasStoredPassword =
          !properties.getProperty(GitSyncProperties.CREDENTIAL_PASSWORD, "").isEmpty();
      credentialPasswordField.setToolTipText(
          hasStoredPassword
              ? "A credential is stored. Leave blank to keep the existing value, "
                  + "or enter a new value to replace it."
              : "Enter your HTTPS token or password.");
      updateCredentialFieldVisibility();

      authorNameField.setText(
          properties.getProperty(
              GitSyncProperties.COMMIT_AUTHOR_NAME, GitSyncProperties.DEFAULT_AUTHOR_NAME));
      authorEmailField.setText(
          properties.getProperty(
              GitSyncProperties.COMMIT_AUTHOR_EMAIL, GitSyncProperties.DEFAULT_AUTHOR_EMAIL));
      commitBranchPatternField.setText(
          properties.getProperty(
              GitSyncProperties.COMMIT_BRANCH_PATTERN,
              GitSyncProperties.DEFAULT_COMMIT_BRANCH_PATTERN));

      syncChannelsCheckBox.setSelected(
          Boolean.parseBoolean(properties.getProperty(GitSyncProperties.SYNC_CHANNELS, "true")));
      syncCodeTemplatesCheckBox.setSelected(
          Boolean.parseBoolean(
              properties.getProperty(GitSyncProperties.SYNC_CODE_TEMPLATES, "true")));
      syncGlobalScriptsCheckBox.setSelected(
          Boolean.parseBoolean(
              properties.getProperty(GitSyncProperties.SYNC_GLOBAL_SCRIPTS, "true")));
      syncChannelGroupsCheckBox.setSelected(
          Boolean.parseBoolean(
              properties.getProperty(GitSyncProperties.SYNC_CHANNEL_GROUPS, "true")));

      setSaveEnabled(false);
    } finally {
      refreshing.set(false);
    }
  }

  private Properties getProperties() {
    Properties properties = new Properties();
    properties.setProperty(GitSyncProperties.ENABLED, String.valueOf(enabledCheckBox.isSelected()));
    properties.setProperty(GitSyncProperties.ENVIRONMENT_NAME, environmentNameField.getText());
    properties.setProperty(GitSyncProperties.NODE_ROLE, (String) nodeRoleCombo.getSelectedItem());
    properties.setProperty(GitSyncProperties.REPO_PATH, repoPathField.getText());
    properties.setProperty(GitSyncProperties.REMOTE_URL, remoteUrlField.getText());
    properties.setProperty(GitSyncProperties.BRANCH, branchField.getText());
    properties.setProperty(
        GitSyncProperties.PUSH_ENABLED, String.valueOf(pushEnabledCheckBox.isSelected()));
    properties.setProperty(
        GitSyncProperties.CREDENTIAL_TYPE, (String) credentialTypeCombo.getSelectedItem());
    properties.setProperty(
        GitSyncProperties.CREDENTIAL_USERNAME, credentialUsernameField.getText());
    // Send blank if the user didn't type anything — the server preserves the existing value in that
    // case.
    char[] typed = credentialPasswordField.getPassword();
    try {
      properties.setProperty(
          GitSyncProperties.CREDENTIAL_PASSWORD, typed.length == 0 ? "" : new String(typed));
    } finally {
      Arrays.fill(typed, '\0');
    }
    properties.setProperty(GitSyncProperties.COMMIT_AUTHOR_NAME, authorNameField.getText());
    properties.setProperty(GitSyncProperties.COMMIT_AUTHOR_EMAIL, authorEmailField.getText());
    properties.setProperty(
        GitSyncProperties.COMMIT_BRANCH_PATTERN, commitBranchPatternField.getText());
    properties.setProperty(
        GitSyncProperties.SYNC_CHANNELS, String.valueOf(syncChannelsCheckBox.isSelected()));
    properties.setProperty(
        GitSyncProperties.SYNC_CODE_TEMPLATES,
        String.valueOf(syncCodeTemplatesCheckBox.isSelected()));
    properties.setProperty(
        GitSyncProperties.SYNC_GLOBAL_SCRIPTS,
        String.valueOf(syncGlobalScriptsCheckBox.isSelected()));
    properties.setProperty(
        GitSyncProperties.SYNC_CHANNEL_GROUPS,
        String.valueOf(syncChannelGroupsCheckBox.isSelected()));
    return properties;
  }

  private boolean validateFields() {
    if (enabledCheckBox.isSelected() && repoPathField.getText().trim().isEmpty()) {
      parent.alertError(parent, "Repository path is required when Git Sync is enabled.");
      return false;
    }
    if (enabledCheckBox.isSelected() && branchField.getText().trim().isEmpty()) {
      parent.alertError(parent, "Branch name is required.");
      return false;
    }
    CredentialType credType = CredentialType.parse((String) credentialTypeCombo.getSelectedItem());
    if (credType.requiresUsernamePassword()
        && credentialPasswordField.getPassword().length == 0
        && !hasStoredPassword) {
      parent.alertError(
          parent, "Credential password/token is required for the selected authentication type.");
      return false;
    }
    return true;
  }

  private void markDirty() {
    if (!refreshing.get()) {
      setSaveEnabled(true);
    }
  }

  private void updateCredentialFieldVisibility() {
    CredentialType credType = CredentialType.parse((String) credentialTypeCombo.getSelectedItem());
    boolean showUsernamePassword = credType.requiresUsernamePassword();
    credentialUsernameLabel.setVisible(showUsernamePassword);
    credentialUsernameField.setVisible(showUsernamePassword);
    credentialPasswordLabel.setVisible(showUsernamePassword);
    credentialPasswordField.setVisible(showUsernamePassword);
  }

  private DocumentListener changeListener =
      new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent e) {
          markDirty();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
          markDirty();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
          markDirty();
        }
      };

  private void initComponents() {
    setBackground(UIConstants.BACKGROUND_COLOR);

    // Status bar
    statusIndicator = new JLabel("Unknown");
    statusIndicator.setFont(statusIndicator.getFont().deriveFont(Font.BOLD));
    lastSyncLabel = new JLabel("-");
    lastCommitLabel = new JLabel("-");
    syncCountLabel = new JLabel("0");
    errorCountLabel = new JLabel("0");
    pendingCountLabel = new JLabel("0");
    pendingCountLabel.setFont(pendingCountLabel.getFont().deriveFont(Font.BOLD));

    // General
    enabledCheckBox = new JCheckBox("Enable Git Sync");
    enabledCheckBox.setBackground(UIConstants.BACKGROUND_COLOR);
    enabledCheckBox.addActionListener(e -> markDirty());

    environmentNameField = new MirthTextField();
    environmentNameField.getDocument().addDocumentListener(changeListener);

    nodeRoleCombo =
        new JComboBox<>(
            new String[] {
              NodeRole.BOTH.name(), NodeRole.CONTRIBUTOR.name(), NodeRole.RECEIVER.name()
            });
    nodeRoleCombo.setToolTipText(
        "CONTRIBUTOR: users edit channels here and commit to Git. "
            + "RECEIVER: receives changes from Git via Import. BOTH: full functionality.");
    nodeRoleCombo.addActionListener(
        e -> {
          applyRoleVisibility(NodeRole.parse((String) nodeRoleCombo.getSelectedItem()));
          markDirty();
        });

    // Repository
    repoPathField = new MirthTextField();
    repoPathField.getDocument().addDocumentListener(changeListener);

    remoteUrlField = new MirthTextField();
    remoteUrlField.setToolTipText(
        "HTTPS or SSH URL of the remote Git repository. Leave blank for local-only.");
    remoteUrlField.getDocument().addDocumentListener(changeListener);

    branchField = new MirthTextField();
    branchField.setToolTipText(
        "The trunk branch. Contributors merge their feature branches into this. "
            + "Receivers pull from this. Typically 'main' or 'master'.");
    branchField.getDocument().addDocumentListener(changeListener);

    pushEnabledCheckBox = new JCheckBox("Push to remote after each commit");
    pushEnabledCheckBox.setBackground(UIConstants.BACKGROUND_COLOR);
    pushEnabledCheckBox.addActionListener(e -> markDirty());

    // Credentials
    credentialTypeCombo =
        new JComboBox<>(
            new String[] {
              CredentialType.NONE.name(),
              CredentialType.HTTPS_TOKEN.name(),
              CredentialType.HTTPS_BASIC.name(),
              CredentialType.SSH_KEY.name()
            });
    credentialTypeCombo.addActionListener(
        e -> {
          updateCredentialFieldVisibility();
          markDirty();
        });

    credentialUsernameLabel = new JLabel("Username:");
    credentialUsernameField = new MirthTextField();
    credentialUsernameField.getDocument().addDocumentListener(changeListener);

    credentialPasswordLabel = new JLabel("Password / Token:");
    credentialPasswordField = new JPasswordField();
    credentialPasswordField.getDocument().addDocumentListener(changeListener);

    // Commit
    authorNameField = new MirthTextField();
    authorNameField.getDocument().addDocumentListener(changeListener);

    authorEmailField = new MirthTextField();
    authorEmailField.getDocument().addDocumentListener(changeListener);

    commitBranchPatternField = new MirthTextField();
    commitBranchPatternField.setToolTipText("Tokens: {username} {date} {environment} {branch}");
    commitBranchPatternField.getDocument().addDocumentListener(changeListener);

    // Scope
    syncChannelsCheckBox = new JCheckBox("Channels");
    syncChannelsCheckBox.setBackground(UIConstants.BACKGROUND_COLOR);
    syncChannelsCheckBox.addActionListener(e -> markDirty());

    syncCodeTemplatesCheckBox = new JCheckBox("Code Templates");
    syncCodeTemplatesCheckBox.setBackground(UIConstants.BACKGROUND_COLOR);
    syncCodeTemplatesCheckBox.addActionListener(e -> markDirty());

    syncGlobalScriptsCheckBox = new JCheckBox("Global Scripts");
    syncGlobalScriptsCheckBox.setBackground(UIConstants.BACKGROUND_COLOR);
    syncGlobalScriptsCheckBox.addActionListener(e -> markDirty());

    syncChannelGroupsCheckBox = new JCheckBox("Channel Groups");
    syncChannelGroupsCheckBox.setBackground(UIConstants.BACKGROUND_COLOR);
    syncChannelGroupsCheckBox.addActionListener(e -> markDirty());

    // Sync log table
    syncLogModel =
        new DefaultTableModel(
            new String[] {"Time", "Action", "Artefact", "Commit", "Status", "Error"}, 0) {
          @Override
          public boolean isCellEditable(int row, int column) {
            return false;
          }
        };
    syncLogTable = new JTable(syncLogModel);
    syncLogTable.setRowHeight(20);
    syncLogTable.getColumnModel().getColumn(0).setPreferredWidth(140);
    syncLogTable.getColumnModel().getColumn(1).setPreferredWidth(60);
    syncLogTable.getColumnModel().getColumn(2).setPreferredWidth(200);
    syncLogTable.getColumnModel().getColumn(3).setPreferredWidth(70);
    syncLogTable.getColumnModel().getColumn(4).setPreferredWidth(50);
    syncLogTable.getColumnModel().getColumn(5).setPreferredWidth(200);
  }

  private void initLayout() {
    setLayout(
        new MigLayout("insets 12, novisualpadding, hidemode 3, gap 6", "[right][left,grow]", "[]"));

    // Status bar
    JPanel statusPanel =
        new JPanel(new MigLayout("insets 8, novisualpadding, gap 12", "[][][][][][][][][][][][]"));
    statusPanel.setBackground(UIConstants.BACKGROUND_COLOR);
    statusPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            "Status",
            TitledBorder.DEFAULT_JUSTIFICATION,
            TitledBorder.DEFAULT_POSITION));

    statusPanel.add(new JLabel("State:"));
    statusPanel.add(statusIndicator, "w 180!");
    statusPanel.add(new JLabel("Pending:"));
    statusPanel.add(pendingCountLabel, "w 40!");
    statusPanel.add(new JLabel("Last Sync:"));
    statusPanel.add(lastSyncLabel, "w 140!");
    statusPanel.add(new JLabel("Last Commit:"));
    statusPanel.add(lastCommitLabel, "w 70!");
    statusPanel.add(new JLabel("Syncs:"));
    statusPanel.add(syncCountLabel, "w 40!");
    statusPanel.add(new JLabel("Errors:"));
    statusPanel.add(errorCountLabel, "w 40!");

    add(statusPanel, "span 2, growx, wrap para");

    // General section
    add(enabledCheckBox, "span 2, left, wrap");
    add(new JLabel("Node Role:"));
    add(nodeRoleCombo, "w 200!, wrap");
    add(new JLabel("Environment Name:"));
    add(environmentNameField, "w 200!, wrap para");

    // Repository section
    JPanel repoPanel =
        new JPanel(
            new MigLayout("insets 8, novisualpadding, hidemode 3, gap 6", "[right][grow,fill]"));
    repoPanel.setBackground(UIConstants.BACKGROUND_COLOR);
    repoPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            "Repository",
            TitledBorder.DEFAULT_JUSTIFICATION,
            TitledBorder.DEFAULT_POSITION));

    repoPanel.add(new JLabel("Local Repo Path:"));
    repoPanel.add(repoPathField, "wrap");
    repoPanel.add(new JLabel("Remote URL:"));
    repoPanel.add(remoteUrlField, "wrap");
    JLabel baseBranchLabel = new JLabel("Base Branch:");
    baseBranchLabel.setToolTipText(
        "The trunk branch. Contributors merge their feature branches into this. "
            + "Receivers pull from this. Typically 'main' or 'master'.");
    repoPanel.add(baseBranchLabel);
    repoPanel.add(branchField, "w 200!, wrap");
    repoPanel.add(pushEnabledCheckBox, "span 2, left, wrap");

    add(repoPanel, "span 2, growx, wrap para");

    // Credentials section
    JPanel credPanel =
        new JPanel(
            new MigLayout("insets 8, novisualpadding, hidemode 3, gap 6", "[right][grow,fill]"));
    credPanel.setBackground(UIConstants.BACKGROUND_COLOR);
    credPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            "Authentication",
            TitledBorder.DEFAULT_JUSTIFICATION,
            TitledBorder.DEFAULT_POSITION));

    credPanel.add(new JLabel("Type:"));
    credPanel.add(credentialTypeCombo, "w 200!, wrap");
    credPanel.add(credentialUsernameLabel);
    credPanel.add(credentialUsernameField, "wrap");
    credPanel.add(credentialPasswordLabel);
    credPanel.add(credentialPasswordField, "wrap");

    add(credPanel, "span 2, growx, wrap para");

    // Commit section
    JPanel commitPanel =
        new JPanel(
            new MigLayout("insets 8, novisualpadding, hidemode 3, gap 6", "[right][grow,fill]"));
    commitPanel.setBackground(UIConstants.BACKGROUND_COLOR);
    commitPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            "Commit Author",
            TitledBorder.DEFAULT_JUSTIFICATION,
            TitledBorder.DEFAULT_POSITION));

    commitPanel.add(new JLabel("Name:"));
    commitPanel.add(authorNameField, "wrap");
    commitPanel.add(new JLabel("Email:"));
    commitPanel.add(authorEmailField, "wrap");
    commitPanel.add(new JLabel("Branch Pattern:"));
    commitPanel.add(commitBranchPatternField, "wrap");

    add(commitPanel, "span 2, growx, wrap para");

    // Scope section
    JPanel scopePanel = new JPanel(new MigLayout("insets 8, novisualpadding, hidemode 3, gap 6"));
    scopePanel.setBackground(UIConstants.BACKGROUND_COLOR);
    scopePanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            "Sync Scope",
            TitledBorder.DEFAULT_JUSTIFICATION,
            TitledBorder.DEFAULT_POSITION));

    scopePanel.add(syncChannelsCheckBox);
    scopePanel.add(syncCodeTemplatesCheckBox);
    scopePanel.add(syncGlobalScriptsCheckBox);
    scopePanel.add(syncChannelGroupsCheckBox);

    add(scopePanel, "span 2, growx, wrap para");

    // Sync log table
    JPanel logPanel =
        new JPanel(new MigLayout("insets 8, novisualpadding, fill", "[grow,fill]", "[grow,fill]"));
    logPanel.setBackground(UIConstants.BACKGROUND_COLOR);
    logPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(),
            "Recent Sync Log",
            TitledBorder.DEFAULT_JUSTIFICATION,
            TitledBorder.DEFAULT_POSITION));

    JScrollPane scrollPane = new JScrollPane(syncLogTable);
    logPanel.add(scrollPane, "grow");

    add(logPanel, "span 2, growx, h 200!, wrap push");
  }

  /**
   * Short 8-char commit hash for display. Duplicated from GitRepoManager rather than referenced,
   * because GitRepoManager lives in the server JAR and the settings panel lives in the client JAR —
   * the OpenWebStart classloader sees only the client JAR at runtime.
   */
  private static String shortHash(String hash) {
    if (hash == null) {
      return "?";
    }
    return hash.length() > 8 ? hash.substring(0, 8) : hash;
  }
}
