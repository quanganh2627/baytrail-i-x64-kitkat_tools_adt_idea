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
package com.android.tools.idea.configurations;

import com.android.SdkConstants;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.resources.ResourceType;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.devices.Device;
import com.android.sdklib.devices.DeviceManager;
import com.android.tools.idea.rendering.Locale;
import com.android.tools.idea.rendering.ManifestInfo;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.HashSet;
import org.jetbrains.android.dom.resources.ResourceElement;
import org.jetbrains.android.dom.resources.ResourceValue;
import org.jetbrains.android.dom.resources.Style;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.sdk.*;
import org.jetbrains.android.uipreview.UserDeviceManager;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.android.sdklib.devices.DeviceManager.DEFAULT_DEVICES;
import static com.android.sdklib.devices.DeviceManager.VENDOR_DEVICES;

/**
 * A {@linkplain ConfigurationManager} is responsible for managing {@link Configuration}
 * objects for a given project.
 * <p>
 * Whereas a {@link Configuration} is tied to a specific render target or theme,
 * the {@linkplain ConfigurationManager} knows the set of available targets, themes,
 * locales etc. for the current project.
 * <p>
 * The {@linkplain ConfigurationManager} is also responsible for storing and retrieving
 * the saved configuration state for a given file.
 */
public class ConfigurationManager implements Disposable {
  @NotNull private final Module myModule;
  private List<Device> myDevices;
  private List<String> myProjectThemes;
  private List<IAndroidTarget> myTargets;
  private final UserDeviceManager myUserDeviceManager;

  private List<String> myCachedFrameworkThemes;
  private IAndroidTarget myCachedFrameworkThemeKey;
  private List<Locale> myLocales;

  private ConfigurationManager(@NotNull Module module) {
    myModule = module;

    myUserDeviceManager = new UserDeviceManager() {
      @Override
      protected void userDevicesChanged() {
        // Force refresh
        myDevices = null;
        // TODO: How do I trigger changes in the UI?
      }
    };
    Disposer.register(this, myUserDeviceManager);
  }

  /**
   * Creates a new {@link Configuration} associated with this manager
   * @return a new {@link Configuration}
   * */
  @NotNull
  public Configuration create(@Nullable VirtualFile file) {
    ConfigurationStateManager stateManager = getStateManager();
    ConfigurationProjectState projectState = stateManager.getProjectState();
    ConfigurationFileState fileState;
    FolderConfiguration config = null;
    if (file != null) {
      fileState = stateManager.getConfigurationState(file);
      // TODO: Use ResourceFolder resFolder = myResources.getResourceFolder(parent) instead
      config = FolderConfiguration.getConfigForFolder(file.getParent().getName());
    } else {
      fileState = null;
    }
    if (config == null) {
      config = new FolderConfiguration();
    }
    Configuration configuration = Configuration.create(this, projectState, fileState, config);

    ConfigurationMatcher matcher = new ConfigurationMatcher(configuration, file);
    if (fileState != null) {
      matcher.adaptConfigSelection(false);
    } else {
      matcher.findAndSetCompatibleConfig(false);
    }

    return configuration;
  }

  /** Returns the associated persistence manager */
  public ConfigurationStateManager getStateManager() {
    return ConfigurationStateManager.get(myModule.getProject());
  }

  /**
   * Creates a new {@link ConfigurationManager} for the given module
   *
   * @param module the associated module
   * @return a new {@link ConfigurationManager}
   */
  @NotNull
  public static ConfigurationManager create(@NotNull Module module) {
    return new ConfigurationManager(module);
  }

  @Nullable
  private AndroidPlatform getPlatform() {
    // TODO: How do we refresh this if the user remaps chosen target?
    final Sdk sdk = ModuleRootManager.getInstance(myModule).getSdk();
    if (sdk != null && sdk.getSdkType() instanceof AndroidSdkType) {
      final AndroidSdkAdditionalData additionalData = (AndroidSdkAdditionalData)sdk.getSdkAdditionalData();
      if (additionalData != null) {
        return additionalData.getAndroidPlatform();
      }
    }
    return null;
  }

  /** Returns the list of available devices for the current platform, if any */
  @NotNull
  public List<Device> getDevices() {
    if (myDevices == null) {
      List<Device> devices = null;

      AndroidPlatform platform = getPlatform();
      if (platform != null) {
        final AndroidSdkData sdkData = platform.getSdkData();
        devices = new ArrayList<Device>();
        DeviceManager deviceManager = sdkData.getDeviceManager();
        devices.addAll(deviceManager.getDevices((DEFAULT_DEVICES | VENDOR_DEVICES)));
        devices.addAll(myUserDeviceManager.parseUserDevices(new MessageBuildingSdkLog()));
      }

      if (devices == null) {
        myDevices = Collections.emptyList();
      } else {
        myDevices = devices;
      }
    }

    return myDevices;
  }

  @NotNull
  public List<IAndroidTarget> getTargets() {
    if (myTargets == null) {
      List<IAndroidTarget> targets = new ArrayList<IAndroidTarget>();

      AndroidPlatform platform = getPlatform();
      if (platform != null) {
        final AndroidSdkData sdkData = platform.getSdkData();

        for (IAndroidTarget target : sdkData.getTargets()) {
          if (target.isPlatform() && target.hasRenderingLibrary()) {
            targets.add(target);
          }
        }
      }

      myTargets = targets;
    }

    return myTargets;
  }

  /**
   * Returns the preferred theme, or null
   */
  @NotNull
  public String computePreferredTheme(@NotNull Configuration configuration) {
    ManifestInfo manifest = ManifestInfo.get(myModule);

    // TODO: If we are rendering a layout in included context, pick the theme
    // from the outer layout instead

    String activity = configuration.getActivity();
    if (activity != null) {
      Map<String, String> activityThemes = manifest.getActivityThemes();
      String theme = activityThemes.get(activity);
      if (theme != null) {
        return theme;
      }
    }

    // Look up the default/fallback theme to use for this project (which
    // depends on the screen size when no particular theme is specified
    // in the manifest)
    return manifest.getDefaultTheme(configuration.getTarget(), configuration.getScreenSize());
  }

  @NotNull
  public List<String> getProjectThemes() {
    if (myProjectThemes == null) {
      // TODO: How do we invalidate this if the manifest theme set changes?
      myProjectThemes = computeProjectThemes();
    }

    return myProjectThemes;
  }

  @NotNull
  private List<String> computeProjectThemes() {
    final AndroidFacet facet = AndroidFacet.getInstance(myModule);
    if (facet == null) {
      return Collections.emptyList();
    }

    final List<String> themes = new ArrayList<String>();
    final Map<String, ResourceElement> styleMap = buildStyleMap(facet);

    for (ResourceElement style : styleMap.values()) {
      if (isTheme(style, styleMap, new HashSet<ResourceElement>())) {
        final String themeName = style.getName().getValue();
        if (themeName != null) {
          final String theme = SdkConstants.STYLE_RESOURCE_PREFIX + themeName;
          themes.add(theme);
        }
      }
    }

    Collections.sort(themes);
    return themes;
  }

  @NotNull
  public List<String> getFrameworkThemes(@Nullable IAndroidTarget target) {
    if (target == myCachedFrameworkThemeKey && target != null) {
      return myCachedFrameworkThemes;
    }

    List<String> themes = computeFrameworkThemes(target);
    myCachedFrameworkThemeKey = target;
    myCachedFrameworkThemes = themes;

    return myCachedFrameworkThemes;
  }

  @NotNull
  public Module getModule() {
    return myModule;
  }

  @NotNull
  public Project getProject() {
    return myModule.getProject();
  }

  @NotNull
  private List<String> computeFrameworkThemes(@Nullable IAndroidTarget target) {
    final AndroidFacet facet = AndroidFacet.getInstance(myModule);
    if (facet == null) {
      return Collections.emptyList();
    }

    final Module module = facet.getModule();
    AndroidTargetData targetData = null;
    final AndroidPlatform androidPlatform = AndroidPlatform.getInstance(module);
    if (androidPlatform != null) {
      if (target == null) {
        target = androidPlatform.getTarget();
      }
      targetData = androidPlatform.getSdkData().getTargetData(target);
    }

    return collectFrameworkThemes(facet, targetData);
  }

  @Override
  public void dispose() {
    myUserDeviceManager.dispose();
  }

  @NotNull
  private static List<String> collectFrameworkThemes(
    @Nullable AndroidFacet facet,
    @Nullable AndroidTargetData targetData) {
    if (targetData != null) {
      final List<String> frameworkThemeNames = new ArrayList<String>(targetData.getThemes(facet));
      Collections.sort(frameworkThemeNames);
      final List<String> themes = new ArrayList<String>();
      for (String themeName : frameworkThemeNames) {
        String theme = SdkConstants.ANDROID_STYLE_RESOURCE_PREFIX + themeName;
        themes.add(theme);
      }

      return themes;
    }

    return Collections.emptyList();
  }


  private static Map<String, ResourceElement> buildStyleMap(AndroidFacet facet) {
    final Map<String, ResourceElement> result = new HashMap<String, ResourceElement>();
    final List<ResourceElement> styles = facet.getLocalResourceManager().getValueResources(ResourceType.STYLE.getName());
    for (ResourceElement style : styles) {
      final String styleName = style.getName().getValue();
      if (styleName != null) {
        result.put(styleName, style);
      }
    }
    return result;
  }

  private static boolean isTheme(ResourceElement resElement, Map<String, ResourceElement> styleMap, Set<ResourceElement> visitedElements) {
    if (!visitedElements.add(resElement)) {
      return false;
    }

    if (!(resElement instanceof Style)) {
      return false;
    }

    final String styleName = resElement.getName().getValue();
    if (styleName == null) {
      return false;
    }

    final ResourceValue parentStyleRef = ((Style)resElement).getParentStyle().getValue();
    String parentStyleName = null;
    boolean frameworkStyle = false;

    if (parentStyleRef != null) {
      final String s = parentStyleRef.getResourceName();
      if (s != null) {
        parentStyleName = s;
        frameworkStyle = AndroidUtils.SYSTEM_RESOURCE_PACKAGE.equals(parentStyleRef.getPackage());
      }
    }

    if (parentStyleRef == null) {
      final int index = styleName.indexOf('.');
      if (index >= 0) {
        parentStyleName = styleName.substring(0, index);
      }
    }

    if (parentStyleRef != null) {
      if (frameworkStyle) {
        return parentStyleName.equals("Theme") || parentStyleName.startsWith("Theme.");
      }
      else {
        final ResourceElement parentStyle = styleMap.get(parentStyleName);
        if (parentStyle != null) {
          return isTheme(parentStyle, styleMap, visitedElements);
        }
      }
    }

    return false;
  }

  @Nullable
  public Device getDefaultDevice() {
    // TODO: Persistence
    // TODO: Pick
    List<Device> devices = getDevices();
    if (!devices.isEmpty()) {
      return devices.get(0);
    }

    return null;
  }

  /**
   * Return the default render target to use, or null if no strong preference
   */
  @Nullable
  public IAndroidTarget getDefaultTarget() {
    // Use the most recent target
    List<IAndroidTarget> targetList = getTargets();
    for (int i = targetList.size() - 1; i >= 0; i--) {
      IAndroidTarget target = targetList.get(i);
      if (target.hasRenderingLibrary()) {
        return target;
      }
    }

    return null;
  }

  public List<Locale> getLocales() {
    if (myLocales == null) {
      // TODO: Compute from the project
      myLocales = Collections.singletonList(Locale.ANY);
    }

    return myLocales;
  }

  @Nullable
  public IAndroidTarget getProjectTarget() {
    AndroidPlatform platform = getPlatform();
    return platform != null ? platform.getTarget() : null;
  }

  @NotNull
  public Locale getLocale() {
    return getStateManager().getProjectState().getLocale(this);
  }

  public void setLocale(@NotNull Locale locale) {
    getStateManager().getProjectState().setLocale(locale);
  }

  @Nullable
  public IAndroidTarget getTarget() {
    return getStateManager().getProjectState().getTarget(this);
  }

  public void setTarget(@NotNull IAndroidTarget target) {
    getStateManager().getProjectState().setTarget(target);
  }

  // --- Old theme code from the plugin; update/rewrite

  //@NotNull
  //private static String getDefaultTheme(@Nullable IAndroidTarget target,
  //                                      @Nullable IAndroidTarget renderingTarget,
  //                                      @Nullable ScreenSize screenSize) {
  //  final int targetApiLevel = target != null ? target.getVersion().getApiLevel() : 0;
  //
  //  final int renderingTargetApiLevel = renderingTarget != null
  //                                      ? renderingTarget.getVersion().getApiLevel()
  //                                      : targetApiLevel;
  //
  //  return targetApiLevel >= 11 && renderingTargetApiLevel >= 11 && screenSize == ScreenSize.XLARGE
  //         ? SdkConstants.ANDROID_STYLE_RESOURCE_PREFIX + "Theme.Holo"
  //         : SdkConstants.ANDROID_STYLE_RESOURCE_PREFIX + "Theme";
  //}
  //
  //@NotNull
  //private List<String> computeManifestThemes() {
  //  final AndroidFacet facet = AndroidFacet.getInstance(myModule);
  //  if (facet == null) {
  //    return Collections.emptyList();
  //  }
  //
  //  // TODO: How do we invalidate this if the manifest theme set changes?
  //  final ArrayList<String> projectThemes = new ArrayList<String>();
  //  collectThemesFromManifest(facet, projectThemes, true);
  //
  //  return projectThemes;
  //}
  //
  //private void collectThemesFromManifest(final AndroidFacet facet,
  //                                       final List<String> resultList,
  //                                       final boolean fromProject) {
  //  ApplicationManager.getApplication().runReadAction(new Runnable() {
  //    @Override
  //    public void run() {
  //      doCollectThemesFromManifest(facet, resultList, fromProject);
  //    }
  //  });
  //}
  //
  //private void doCollectThemesFromManifest(AndroidFacet facet,
  //                                         List<String> resultList,
  //                                         boolean fromProject) {
  //  final Manifest manifest = facet.getManifest();
  //  if (manifest == null) {
  //    return;
  //  }
  //
  //  final Application application = manifest.getApplication();
  //  if (application == null) {
  //    return;
  //  }
  //
  //  final List<ThemeData> activityThemesList = new ArrayList<ThemeData>();
  //
  //  final XmlTag applicationTag = application.getXmlTag();
  //  ThemeData preferredTheme = null;
  //  if (applicationTag != null) {
  //    final String applicationThemeRef = applicationTag.getAttributeValue("theme", SdkConstants.NS_RESOURCES);
  //    if (applicationThemeRef != null) {
  //      preferredTheme = getThemeByRef(applicationThemeRef);
  //    }
  //  }
  //
  //  if (preferredTheme == null) {
  //    final AndroidPlatform platform = AndroidPlatform.getInstance(facet.getModule());
  //    final IAndroidTarget target = platform != null ? platform.getTarget() : null;
  //    final IAndroidTarget renderingTarget = getSelectedTarget();
  //    final State configuration = getSelectedDeviceConfiguration();
  //    final ScreenSize screenSize = configuration != null
  //                                  ? configuration.getHardware().getScreen().getSize()
  //                                  : null;
  //    preferredTheme = getThemeByRef(getDefaultTheme(target, renderingTarget, screenSize));
  //  }
  //
  //  final HashSet<String> addedThemes = new HashSet<String>();
  //  if (!addedThemes.contains(preferredTheme) && fromProject == preferredTheme.isProjectTheme()) {
  //    addedThemes.add(preferredTheme);
  //    resultList.add(preferredTheme);
  //  }
  //
  //  for (Activity activity : application.getActivities()) {
  //    final XmlTag activityTag = activity.getXmlTag();
  //    if (activityTag != null) {
  //      final String activityThemeRef = activityTag.getAttributeValue("theme", SdkConstants.NS_RESOURCES);
  //      if (activityThemeRef != null) {
  //        final ThemeData activityTheme = getThemeByRef(activityThemeRef);
  //        if (!addedThemes.contains(activityTheme) && fromProject == activityTheme.isProjectTheme()) {
  //          addedThemes.add(activityTheme);
  //          activityThemesList.add(activityTheme);
  //        }
  //      }
  //    }
  //  }
  //
  //  Collections.sort(activityThemesList);
  //  resultList.addAll(activityThemesList);
  //}

  //@Nullable
  //public String getDefaultTheme(IAndroidTarget target) {
  //  List<String> projectThemes = getProjectThemes();
  //  if (!projectThemes.isEmpty()) {
  //    return projectThemes.get(0);
  //  }
  //
  //  List<String> frameworkThemes = getFrameworkThemes(target);
  //  if (!frameworkThemes.isEmpty()) {
  //    return frameworkThemes.get(0);
  //  }
  //
  //  return null;
  //}

}