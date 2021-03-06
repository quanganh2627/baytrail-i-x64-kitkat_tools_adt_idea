/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.android.exportSignedPackage;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.wizard.CommitStepException;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.android.compiler.AndroidCompileUtil;
import org.jetbrains.android.compiler.AndroidProguardCompiler;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.facet.AndroidFacetConfiguration;
import org.jetbrains.android.facet.AndroidRootUtil;
import org.jetbrains.android.sdk.AndroidPlatform;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.android.util.AndroidCommonUtils;
import org.jetbrains.android.util.SaveFileListener;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

/**
 * @author Eugene.Kudelevsky
 */
class ApkStep extends ExportSignedPackageWizardStep {
  public static final String APK_PATH_PROPERTY = "ExportedApkPath";
  public static final String APK_PATH_PROPERTY_UNSIGNED = "ExportedUnsignedApkPath";
  public static final String RUN_PROGUARD_PROPERTY = "AndroidRunProguardForReleaseBuild";
  public static final String PROGUARD_CFG_PATH_PROPERTY = "AndroidProguardConfigPath";
  public static final String INCLUDE_SYSTEM_PROGUARD_FILE_PROPERTY = "AndroidIncludeSystemProguardFile";

  private TextFieldWithBrowseButton myApkPathField;
  private JPanel myContentPanel;
  private JLabel myApkPathLabel;
  private JCheckBox myProguardCheckBox;
  private JBLabel myProguardConfigFilePathLabel;
  private TextFieldWithBrowseButton myProguardConfigFilePathField;
  private JCheckBox myIncludeSystemProguardFileCheckBox;

  private final ExportSignedPackageWizard myWizard;
  private boolean myInited;

  @Nullable
  private static String getContentRootPath(Module module) {
    VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
    if (contentRoots.length != 0) {
      VirtualFile contentRoot = contentRoots[0];
      if (contentRoot != null) return contentRoot.getPath();
    }
    return null;
  }

  public ApkStep(ExportSignedPackageWizard wizard) {
    myWizard = wizard;
    myApkPathLabel.setLabelFor(myApkPathField);
    myProguardConfigFilePathLabel.setLabelFor(myProguardConfigFilePathField);

    myApkPathField.getButton().addActionListener(
      new SaveFileListener(myContentPanel, myApkPathField, AndroidBundle.message("android.extract.package.choose.dest.apk"), "apk") {
        @Override
        protected String getDefaultLocation() {
          Module module = myWizard.getFacet().getModule();
          return getContentRootPath(module);
        }
      });

    final Project project = wizard.getProject();
    myProguardConfigFilePathField.getButton().addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        final String path = myProguardConfigFilePathField.getText().trim();
        VirtualFile defaultFile = path != null && path.length() > 0
                                  ? LocalFileSystem.getInstance().findFileByPath(path)
                                  : null;
        final AndroidFacet facet = myWizard.getFacet();

        if (defaultFile == null && facet != null) {
          defaultFile = AndroidRootUtil.getMainContentRoot(facet);
        }
        final FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor();
        final VirtualFile file = FileChooser.chooseFile(descriptor, myContentPanel, project, defaultFile);
        if (file != null) {
          myProguardConfigFilePathField.setText(FileUtil.toSystemDependentName(file.getPath()));
        }
      }
    });

    myProguardCheckBox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        final boolean enabled = myProguardCheckBox.isSelected();
        myProguardConfigFilePathLabel.setEnabled(enabled);
        myProguardConfigFilePathField.setEnabled(enabled);
        myIncludeSystemProguardFileCheckBox.setEnabled(enabled);
      }
    });

    myContentPanel.setPreferredSize(new Dimension(myContentPanel.getPreferredSize().width, 250));
  }

  @Override
  public void _init() {
    if (myInited) return;
    final AndroidFacet facet = myWizard.getFacet();
    Module module = facet.getModule();

    PropertiesComponent properties = PropertiesComponent.getInstance(module.getProject());
    String lastModule = properties.getValue(ChooseModuleStep.MODULE_PROPERTY);
    String lastApkPath = properties.getValue(getApkPathPropertyName());
    if (lastApkPath != null && module.getName().equals(lastModule)) {
      myApkPathField.setText(FileUtil.toSystemDependentName(lastApkPath));
    }
    else {
      String contentRootPath = getContentRootPath(module);
      if (contentRootPath != null) {
        String defaultPath = FileUtil.toSystemDependentName(contentRootPath + "/" + module.getName() + ".apk");
        myApkPathField.setText(defaultPath);
      }
    }

    final String runProguardPropValue = properties.getValue(RUN_PROGUARD_PROPERTY);
    boolean selected;

    if (runProguardPropValue != null) {
      selected = Boolean.parseBoolean(runProguardPropValue);
    }
    else {
      selected = facet.getProperties().RUN_PROGUARD;
    }
    myProguardCheckBox.setSelected(selected);
    myProguardConfigFilePathLabel.setEnabled(selected);
    myProguardConfigFilePathField.setEnabled(selected);
    myIncludeSystemProguardFileCheckBox.setEnabled(selected);

    final AndroidPlatform platform = AndroidPlatform.getInstance(module);
    final int sdkToolsRevision = platform != null ? platform.getSdkData().getSdkToolsRevision() : -1;
    myIncludeSystemProguardFileCheckBox.setVisible(AndroidCommonUtils.isIncludingInProguardSupported(sdkToolsRevision));

    final String proguardCfgPath = properties.getValue(PROGUARD_CFG_PATH_PROPERTY);
    if (proguardCfgPath != null &&
        LocalFileSystem.getInstance().refreshAndFindFileByPath(proguardCfgPath) != null) {
      myProguardConfigFilePathField.setText(FileUtil.toSystemDependentName(proguardCfgPath));
      final String includeSystemProguardFile = properties.getValue(INCLUDE_SYSTEM_PROGUARD_FILE_PROPERTY);
      myIncludeSystemProguardFileCheckBox.setSelected(Boolean.parseBoolean(includeSystemProguardFile));
    }
    else {
      final AndroidFacetConfiguration configuration = facet.getConfiguration();
      if (configuration.getState().RUN_PROGUARD) {
        final VirtualFile proguardCfgFile = AndroidRootUtil.getProguardCfgFile(facet);
        if (proguardCfgFile != null) {
          myProguardConfigFilePathField.setText(FileUtil.toSystemDependentName(proguardCfgFile.getPath()));
        }
        myIncludeSystemProguardFileCheckBox.setSelected(facet.getConfiguration().isIncludeSystemProguardCfgPath());
      }
      else {
        final Pair<VirtualFile, Boolean> pair = AndroidCompileUtil.getDefaultProguardConfigFile(facet);
        if (pair != null) {
          myProguardConfigFilePathField.setText(FileUtil.toSystemDependentName(pair.getFirst().getPath()));
          myIncludeSystemProguardFileCheckBox.setSelected(pair.getSecond());
        }
        else {
          myIncludeSystemProguardFileCheckBox.setSelected(true);
        }
      }
    }

    myInited = true;
  }

  private String getApkPathPropertyName() {
    return myWizard.isSigned() ? APK_PATH_PROPERTY : APK_PATH_PROPERTY_UNSIGNED;
  }

  @Override
  public JComponent getComponent() {
    return myContentPanel;
  }

  @Override
  protected boolean canFinish() {
    return true;
  }

  @Override
  public String getHelpId() {
    return "reference.android.reference.extract.signed.package.specify.apk.location";
  }

  @Override
  public void _commit(boolean finishChosen) throws CommitStepException {
    final String apkPath = myApkPathField.getText().trim();
    if (apkPath.length() == 0) {
      throw new CommitStepException(AndroidBundle.message("android.extract.package.specify.apk.path.error"));
    }

    AndroidFacet facet = myWizard.getFacet();
    PropertiesComponent properties = PropertiesComponent.getInstance(myWizard.getProject());
    properties.setValue(ChooseModuleStep.MODULE_PROPERTY, facet != null ? facet.getModule().getName() : "");
    properties.setValue(getApkPathPropertyName(), apkPath);

    File folder = new File(apkPath).getParentFile();
    if (folder == null) {
      throw new CommitStepException(AndroidBundle.message("android.cannot.create.file.error", apkPath));
    }
    try {
      if (!folder.exists()) {
        folder.mkdirs();
      }
    }
    catch (Exception e) {
      throw new CommitStepException(e.getMessage());
    }

    final CompileScope compileScope = CompilerManager.getInstance(myWizard.getProject()).
      createModuleCompileScope(facet.getModule(), true);
    AndroidCompileUtil.setReleaseBuild(compileScope);

    properties.setValue(RUN_PROGUARD_PROPERTY, Boolean.toString(myProguardCheckBox.isSelected()));

    if (myProguardCheckBox.isSelected()) {
      final String proguardCfgPath = myProguardConfigFilePathField.getText().trim();
      if (proguardCfgPath.length() == 0) {
        throw new CommitStepException(AndroidBundle.message("android.extract.package.specify.proguard.cfg.path.error"));
      }
      properties.setValue(PROGUARD_CFG_PATH_PROPERTY, proguardCfgPath);
      properties.setValue(INCLUDE_SYSTEM_PROGUARD_FILE_PROPERTY, Boolean.toString(myIncludeSystemProguardFileCheckBox.isSelected()));

      if (!new File(proguardCfgPath).isFile()) {
        throw new CommitStepException("Cannot find file " + proguardCfgPath);
      }

      compileScope.putUserData(AndroidProguardCompiler.PROGUARD_CFG_PATH_KEY, proguardCfgPath);
      compileScope.putUserData(AndroidProguardCompiler.INCLUDE_SYSTEM_PROGUARD_FILE, myIncludeSystemProguardFileCheckBox.isSelected());
    }
    myWizard.setCompileScope(compileScope);
    myWizard.setApkPath(apkPath);
  }

  @Override
  protected void commitForNext() throws CommitStepException {
  }
}
