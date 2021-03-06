// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.lightEdit.actions;

import com.intellij.ide.lightEdit.LightEditService;
import com.intellij.idea.ActionsBundle;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public class LightEditNewFileAction extends DumbAwareAction {
  public LightEditNewFileAction() {
    super(() -> ActionsBundle.message("action.LightEditNewFileAction.text"));
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    LightEditService.getInstance().createNewFile();
  }
}
