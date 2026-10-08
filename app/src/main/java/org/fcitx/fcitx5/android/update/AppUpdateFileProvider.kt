/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Local Voice IME Contributors
 */
package org.fcitx.fcitx5.android.update

import androidx.core.content.FileProvider
import org.fcitx.fcitx5.android.R

/** Grants the system installer read access to the verified update, not other app files. */
class AppUpdateFileProvider : FileProvider(R.xml.app_update_paths)
