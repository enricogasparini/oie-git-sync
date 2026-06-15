/*
 * SPDX-License-Identifier: MPL-2.0
 */

package com.mirth.connect.plugins.gitsync;

/**
 * Sanitises user-influenced values before they are interpolated into log messages. Values that
 * originate from REST parameters (usernames, branch names, commit messages) could otherwise carry
 * line terminators that forge additional log lines (log injection).
 */
final class LogSanitiser {

  private LogSanitiser() {}

  /**
   * Returns the value with carriage returns and line feeds replaced by underscores so a single
   * logical value cannot span multiple log lines. Null-safe.
   */
  static String clean(String value) {
    if (value == null) {
      return null;
    }
    return value.replaceAll("[\r\n]", "_");
  }
}
