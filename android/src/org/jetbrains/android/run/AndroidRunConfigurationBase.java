/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

package org.jetbrains.android.run;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.sdklib.internal.avd.AvdInfo;
import com.android.sdklib.internal.avd.AvdManager;
import com.intellij.CommonBundle;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ui.configuration.ClasspathEditor;
import com.intellij.openapi.roots.ui.configuration.ModulesConfigurator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PathUtil;
import org.jdom.Element;
import org.jetbrains.android.dom.manifest.Application;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.facet.AndroidFacetConfiguration;
import org.jetbrains.android.facet.AndroidRootUtil;
import org.jetbrains.android.sdk.AndroidPlatform;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: Eugene.Kudelevsky
 * Date: Aug 27, 2009
 * Time: 2:20:54 PM
 * To change this template use File | Settings | File Templates.
 */
public abstract class AndroidRunConfigurationBase extends ModuleBasedConfiguration<JavaRunConfigurationModule> {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.android.run.AndroidRunConfigurationBase");
  
  public String TARGET_SELECTION_MODE = TargetSelectionMode.EMULATOR.name();
  public String PREFERRED_AVD = "";
  public boolean USE_COMMAND_LINE = true;
  public String COMMAND_LINE = "";
  public boolean WIPE_USER_DATA = false;
  public boolean DISABLE_BOOT_ANIMATION = false;
  public String NETWORK_SPEED = "full";
  public String NETWORK_LATENCY = "none";
  public boolean CLEAR_LOGCAT = false;

  public AndroidRunConfigurationBase(final Project project, final ConfigurationFactory factory) {
    super(new JavaRunConfigurationModule(project, false), factory);
  }

  @Override
  public final void checkConfiguration() throws RuntimeConfigurationException {
    JavaRunConfigurationModule configurationModule = getConfigurationModule();
    configurationModule.checkForWarning();
    Module module = configurationModule.getModule();
    if (module == null) return;
    AndroidFacet facet = AndroidFacet.getInstance(module);
    if (facet == null) {
      throw new RuntimeConfigurationError(AndroidBundle.message("android.no.facet.error"));
    }
    if (facet.isLibraryProject()) {
      Pair<Boolean, String> result = supportsRunningLibraryProjects(facet);
      if (!result.getFirst()) {
        throw new RuntimeConfigurationError(result.getSecond());
      }
    }
    if (facet.getConfiguration().getAndroidPlatform() == null) {
      throw new RuntimeConfigurationError(AndroidBundle.message("select.platform.error"));
    }
    if (facet.getManifest() == null) {
      throw new RuntimeConfigurationError(AndroidBundle.message("android.manifest.not.found.error"));
    }
    if (PREFERRED_AVD.length() > 0) {
      AvdManager avdManager = facet.getAvdManagerSilently();
      if (avdManager == null) {
        throw new RuntimeConfigurationError(AndroidBundle.message("avd.cannot.be.loaded.error"));
      }
      AvdInfo avdInfo = avdManager.getAvd(PREFERRED_AVD, false);
      if (avdInfo == null) {
        throw new RuntimeConfigurationError(AndroidBundle.message("avd.not.found.error", PREFERRED_AVD));
      }
      if (avdInfo.getStatus() != AvdInfo.AvdStatus.OK) {
        String message = avdInfo.getErrorMessage();
        message = AndroidBundle.message("avd.not.valid.error", PREFERRED_AVD) +
                  (message != null ? ": " + message: "") + ". Try to repair it through AVD manager";
        throw new RuntimeConfigurationError(message);
      }
    }
    checkConfiguration(facet);
  }

  /** Returns whether the configuration supports running library projects, and if it doesn't, then an explanation as to why it doesn't. */
  protected abstract Pair<Boolean,String> supportsRunningLibraryProjects(@NotNull AndroidFacet facet);
  protected abstract void checkConfiguration(@NotNull AndroidFacet facet) throws RuntimeConfigurationException;

  @Override
  public Collection<Module> getValidModules() {
    final List<Module> result = new ArrayList<Module>();
    Module[] modules = ModuleManager.getInstance(getProject()).getModules();
    for (Module module : modules) {
      if (AndroidFacet.getInstance(module) != null) {
        result.add(module);
      }
    }
    return result;
  }

  @NotNull
  public TargetSelectionMode getTargetSelectionMode() {
    try {
      return TargetSelectionMode.valueOf(TARGET_SELECTION_MODE);
    }
    catch (IllegalArgumentException e) {
      LOG.info(e);
      return TargetSelectionMode.EMULATOR;
    }
  }
  
  public void setTargetSelectionMode(@NotNull TargetSelectionMode mode) {
    TARGET_SELECTION_MODE = mode.name();
  }

  @Override
  public AndroidRunningState getState(@NotNull final Executor executor, @NotNull ExecutionEnvironment env) throws ExecutionException {
    final Module module = getConfigurationModule().getModule();
    if (module == null) {
      throw new ExecutionException("Module is not found");
    }
    final AndroidFacet facet = AndroidFacet.getInstance(module);
    if (facet == null) {
      throw new ExecutionException(AndroidBundle.message("no.facet.error", module.getName()));
    }

    Project project = env.getProject();

    AndroidFacetConfiguration configuration = facet.getConfiguration();
    AndroidPlatform platform = configuration.getAndroidPlatform();
    if (platform == null) {
      Messages.showErrorDialog(project, AndroidBundle.message("specify.platform.error"), CommonBundle.getErrorTitle());
      ModulesConfigurator.showDialog(project, module.getName(), ClasspathEditor.NAME);
      return null;
    }

    boolean debug = DefaultDebugExecutor.EXECUTOR_ID.equals(executor.getId());
    boolean nonDebuggableOnDevice = false;

    if (debug) {
      final Manifest manifest = facet.getManifest();
      final Application application = manifest != null ? manifest.getApplication() : null;

      nonDebuggableOnDevice = application != null && Boolean.FALSE.toString().
        equals(application.getDebuggable().getStringValue());

      if (!AndroidSdkUtils.activateDdmsIfNecessary(facet.getModule().getProject(), new Computable<AndroidDebugBridge>() {
        @Nullable
        @Override
        public AndroidDebugBridge compute() {
          return facet.getDebugBridge();
        }
      })) {
        return null;
      }
    }

    if (platform.getSdkData().getDebugBridge(getProject()) == null) return null;

    TargetChooser targetChooser = null;
    switch (getTargetSelectionMode()) {
      case SHOW_DIALOG:
        targetChooser = new ManualTargetChooser();
        break;
      case EMULATOR:
        targetChooser = new EmulatorTargetChooser(PREFERRED_AVD.length() > 0 ? PREFERRED_AVD : null);
        break;
      case USB_DEVICE:
        targetChooser = new UsbDeviceTargetChooser();
        break;
      default:
        assert false : "Unknown target selection mode " + TARGET_SELECTION_MODE;
        break;
    }
    
    AndroidApplicationLauncher applicationLauncher = getApplicationLauncher(facet);
    if (applicationLauncher != null) {
      final boolean supportMultipleDevices = supportMultipleDevices() && executor.getId().equals(DefaultRunExecutor.EXECUTOR_ID);
      return new AndroidRunningState(env, facet, targetChooser, computeCommandLine(), applicationLauncher,
                                     supportMultipleDevices, CLEAR_LOGCAT, this, nonDebuggableOnDevice);
    }
    return null;
  }

  @Nullable
  protected static Pair<File, String> getCopyOfCompilerManifestFile(@NotNull AndroidFacet facet, @Nullable ProcessHandler processHandler) {
    final VirtualFile manifestFile = AndroidRootUtil.getCustomManifestFileForCompiler(facet);

    if (manifestFile == null) {
      return null;
    }
    File tmpDir = null;
    try {
      tmpDir = FileUtil.createTempDirectory("android_manifest_file_for_execution", "tmp");
      final File manifestCopy = new File(tmpDir, manifestFile.getName());
      FileUtil.copy(new File(manifestFile.getPath()), manifestCopy);
      return Pair.create(manifestCopy, PathUtil.getLocalPath(manifestFile));
    }
    catch (IOException e) {
      if (processHandler != null) {
        processHandler.notifyTextAvailable("I/O error: " + e.getMessage(), ProcessOutputTypes.STDERR);
      }
      LOG.info(e);
      if (tmpDir != null) {
        FileUtil.delete(tmpDir);
      }
      return null;
    }
  }

  private String computeCommandLine() {
    StringBuilder result = new StringBuilder();
    result.append("-netspeed ").append(NETWORK_SPEED).append(' ');
    result.append("-netdelay ").append(NETWORK_LATENCY).append(' ');
    if (WIPE_USER_DATA) {
      result.append("-wipe-data ");
    }
    if (DISABLE_BOOT_ANIMATION) {
      result.append("-no-boot-anim ");
    }
    if (USE_COMMAND_LINE) {
      result.append(COMMAND_LINE);
    }
    int last = result.length() - 1;
    if (result.charAt(last) == ' ') {
      result.deleteCharAt(last);
    }
    return result.toString();
  }

  @NotNull
  protected abstract ConsoleView attachConsole(AndroidRunningState state, Executor executor) throws ExecutionException;

  @Nullable
  protected abstract AndroidApplicationLauncher getApplicationLauncher(AndroidFacet facet);

  protected abstract boolean supportMultipleDevices();

  @Override
  public void readExternal(Element element) throws InvalidDataException {
    super.readExternal(element);
    readModule(element);
    DefaultJDOMExternalizer.readExternal(this, element);
  }

  @Override
  public void writeExternal(Element element) throws WriteExternalException {
    super.writeExternal(element);
    writeModule(element);
    DefaultJDOMExternalizer.writeExternal(this, element);
  }
}
