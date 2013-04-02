/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea;

import com.android.tools.idea.actions.AndroidNewProjectAction;
import com.intellij.ide.actions.NewProjectAction;
import com.intellij.openapi.actionSystem.*;

public class AndroidIdeSpecificInitializer implements Runnable {
  @Override
  public void run() {
    // Fix New Project actions
    if (Boolean.getBoolean(AndroidNewProjectAction.NEW_NEW_PROJECT_WIZARD)) {
      fixNewProjectActions();
    }
  }

  private static void fixNewProjectActions() {
    // TODO: This is temporary code. We should build out our own menu set and welcome screen exactly how we want.

    // Register the New Project action manually since we need to unregister the platform one
    ActionManager am = ActionManager.getInstance();
    am.unregisterAction("NewProject");

    AnAction action = new AndroidNewProjectAction();
    am.registerAction("NewProject", action);

    DefaultActionGroup ag = (DefaultActionGroup)am.getAction("WelcomeScreen.QuickStart.IDEA");
    AnAction[] children = ag.getChildren(null);
    for (AnAction child : children) {
      if (child instanceof NewProjectAction) {
        ag.remove(child);
      }
    }
    ag.add(action, Constraints.FIRST);
  }
}